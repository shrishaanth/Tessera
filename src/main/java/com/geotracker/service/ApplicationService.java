package com.geotracker.service;

import com.geotracker.geofence.GeofenceEngine;
import com.geotracker.geofence.GeofenceEngine.Zone;
import com.geotracker.index.*;
import com.geotracker.ingestion.ShardRouter;
import com.geotracker.model.*;
import com.geotracker.routing.AStarRouter;
import com.geotracker.routing.DijkstraRouter;
import com.geotracker.routing.GeoUtils;
import com.geotracker.routing.RoadGraph;
import com.geotracker.simulator.GraphVehicleGenerator;
import com.geotracker.simulator.TrajectorySimulator;
import com.geotracker.util.Config;

import com.geotracker.geofence.RayCaster;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backend coordinator for the desktop application: owns the simulation, the sharded spatial indexes,
 * routing and geofencing. UI code talks to the backend only through this class. All public methods are
 * safe to call from any thread; none of them block for longer than a few milliseconds.
 */
public class ApplicationService {

    public enum SimulationState { STOPPED, RUNNING, PAUSED }
    public enum IndexStrategy { COW_QUADTREE, GRID, NAIVE }

    /** Where vehicle trajectories come from. */
    public enum TrajectorySource {
        /** Every vehicle follows the OpenStreetMap-derived road graph. */
        ROAD_GRAPH("Road graph (OSM streets)"),
        /** Vehicles 0-19 replay the bundled CSV (a synthetic 10x10 lattice); the rest follow the road graph. */
        CSV_REPLAY("CSV replay + road graph");

        private final String label;
        TrajectorySource(String label) { this.label = label; }
        public String label() { return label; }
    }

    public static final String SOURCE_CSV = "csv";
    public static final String SOURCE_GENERATED = "generated";
    public static final int[] SUPPORTED_VEHICLE_COUNTS = {20, 100, 1_000, 10_000};

    private static final int CSV_VEHICLE_COUNT = 20;
    private static final int SHARDS = Config.SHARDS;
    private static final int MAX_EVENT_HISTORY = 2_000;
    /** A position change implying more than this is a trajectory loop/teleport, not driving. */
    private static final double MAX_PLAUSIBLE_SPEED_KMH = 250.0;
    private static final Map<String, String> BUILT_IN_ZONE_NAMES = Map.of(
            "gasworks_park", "Gas Works north",
            "north_zone", "North zone");

    private final BoundingBox datasetBounds;
    private final BoundingBox geoBounds;
    private final RoadGraph osmGraph;
    private final RoadGraph gridGraph;
    private final RoadGraph activeGraph;
    private final AStarRouter router;
    private final DijkstraRouter referenceRouter;
    private final TrajectorySimulator trajectorySimulator;

    private final ShardRouter shardRouter;
    private final CowQuadtree[] quadtrees;
    private final HamtIndex[] hamts;
    private final IndexerThread[] indexers;
    private final GeofenceEngine geofenceEngine;

    private volatile SimulationState simulationState = SimulationState.STOPPED;
    private ScheduledExecutorService simulationExecutor;
    private ScheduledExecutorService geofenceExecutor;
    private volatile double simulationSpeedMultiplier = 1.0;
    private final AtomicLong totalUpdates = new AtomicLong(0);
    private final AtomicLong updatesSinceLastCheck = new AtomicLong(0);
    private volatile double measuredUpdateRate = 0.0;
    private volatile long lastRateCheckTime = System.nanoTime();
    /** Simulated seconds elapsed in the current run (advanced by the simulation thread only). */
    private volatile double simClockSec = 0.0;

    private final ArrayDeque<ZoneEvent> eventHistory = new ArrayDeque<>();
    private final AtomicLong enterEvents = new AtomicLong();
    private final AtomicLong exitEvents = new AtomicLong();

    private volatile IndexStrategy currentStrategy = IndexStrategy.COW_QUADTREE;
    private volatile TrajectorySource trajectorySource = TrajectorySource.ROAD_GRAPH;
    private volatile int totalVehicleCount = CSV_VEHICLE_COUNT;
    private volatile GraphVehicleGenerator graphVehicleGenerator;
    private volatile long staleThresholdMs = 2_000;

    /** Last observed motion per vehicle, maintained by the simulation thread. */
    private final ConcurrentHashMap<Long, Motion> motion = new ConcurrentHashMap<>();

    // Metrics sampling state (guarded by sampleMetrics' monitor)
    private static final long RATE_WINDOW_NANOS = 3_000_000_000L;
    /** Recent samples of {nanoTime, generated total, indexed total} for windowed rates. */
    private final List<long[]> rateWindow = new ArrayList<>();
    private SimulationState lastSampleState = null;
    private int sampleCursor = 0;

    private volatile boolean shutDown = false;

    public ApplicationService() {
        osmGraph = loadRoadGraph();
        gridGraph = RoadGraph.builder().addGrid(10, 10, 50.0).build();
        activeGraph = osmGraph != null ? osmGraph : gridGraph;
        router = new AStarRouter(activeGraph);
        referenceRouter = new DijkstraRouter(activeGraph);

        datasetBounds = computeDatasetBounds(activeGraph);
        // The quadtree silently ignores points outside its root bounds, so the root must cover every
        // position a vehicle can reach (road graph + CSV area), with a margin.
        double padX = (datasetBounds.maxX() - datasetBounds.minX()) * 0.1;
        double padY = (datasetBounds.maxY() - datasetBounds.minY()) * 0.1;
        geoBounds = new BoundingBox(datasetBounds.minX() - padX, datasetBounds.minY() - padY,
                datasetBounds.maxX() + padX, datasetBounds.maxY() + padY);

        trajectorySimulator = new TrajectorySimulator(
                Config.TRAJECTORY_RESOURCE, CSV_VEHICLE_COUNT, 1.0, 1.0 / Config.UPDATE_RATE_HZ);

        shardRouter = new ShardRouter(SHARDS, Config.RING_BUFFER_SIZE);
        quadtrees = new CowQuadtree[SHARDS];
        hamts = new HamtIndex[SHARDS];
        indexers = new IndexerThread[SHARDS];

        for (int i = 0; i < SHARDS; i++) {
            quadtrees[i] = new CowQuadtree(geoBounds);
            hamts[i] = new HamtIndex();
            indexers[i] = new IndexerThread(i, shardRouter.getRingBuffer(i),
                    quadtrees[i], hamts[i], Config.PUBLISH_MAX_DIRTY, Config.PUBLISH_INTERVAL_MS);
            indexers[i].setDaemon(true);
            indexers[i].start();
        }

        List<Zone> defaultZones = List.of(
                new Zone("gasworks_park", List.of(
                        new Position(-122.3340, 47.6465, 0),
                        new Position(-122.3310, 47.6465, 0),
                        new Position(-122.3310, 47.6485, 0),
                        new Position(-122.3340, 47.6485, 0)
                ), new BoundingBox(-122.3340, 47.6465, -122.3310, 47.6485)),
                new Zone("north_zone", List.of(
                        new Position(-122.3335, 47.6490, 0),
                        new Position(-122.3305, 47.6490, 0),
                        new Position(-122.3305, 47.6500, 0),
                        new Position(-122.3335, 47.6500, 0)
                ), new BoundingBox(-122.3335, 47.6490, -122.3305, 47.6500))
        );

        geofenceEngine = new GeofenceEngine(indexers, defaultZones);
        rebuildFleet();
    }

    /** The city road graph is immutable once loaded, so it is parsed once per JVM and shared. */
    private static volatile RoadGraph cityGraph;

    private static RoadGraph loadRoadGraph() {
        RoadGraph city = cityGraph;
        if (city == null) {
            synchronized (ApplicationService.class) {
                city = cityGraph;
                if (city == null) {
                    city = loadCityGraph();
                    cityGraph = city;
                }
            }
        }
        return city != null ? city : loadOsmGraph();
    }

    private static RoadGraph loadCityGraph() {
        try (InputStream raw = ApplicationService.class.getResourceAsStream(Config.CITY_ROADGRAPH_RESOURCE)) {
            if (raw == null) return null;
            try (InputStream in = new GZIPInputStream(raw)) {
                return RoadGraph.loadFromJson(in);
            }
        } catch (Exception e) {
            System.err.println("Failed to load city road graph: " + e.getMessage());
            return null;
        }
    }

    private static RoadGraph loadOsmGraph() {
        try (InputStream is = ApplicationService.class.getResourceAsStream(Config.ROADGRAPH_RESOURCE)) {
            if (is == null) return null;
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return RoadGraph.loadFromJson(json);
        } catch (Exception e) {
            System.err.println("Failed to load OSM road graph: " + e.getMessage());
            return null;
        }
    }

    private static BoundingBox computeDatasetBounds(RoadGraph graph) {
        double minX = Config.AREA_MIN_LNG, minY = Config.AREA_MIN_LAT;
        double maxX = Config.AREA_MAX_LNG, maxY = Config.AREA_MAX_LAT;
        if (graph != null && graph.isGeoGraph()) {
            for (RoadGraph.Node n : graph.getAllNodes()) {
                minX = Math.min(minX, n.x());
                minY = Math.min(minY, n.y());
                maxX = Math.max(maxX, n.x());
                maxY = Math.max(maxY, n.y());
            }
        }
        return new BoundingBox(minX, minY, maxX, maxY);
    }

    // --- Fleet configuration (only while stopped) ---

    public synchronized void setVehicleCount(int count) {
        if (simulationState != SimulationState.STOPPED) return;
        int min = trajectorySource == TrajectorySource.CSV_REPLAY ? CSV_VEHICLE_COUNT : 1;
        count = Math.max(min, count);
        if (count == totalVehicleCount && graphVehicleGenerator != null) return;
        this.totalVehicleCount = count;
        rebuildFleet();
        clearTrackedState();
    }

    public synchronized void setTrajectorySource(TrajectorySource source) {
        if (simulationState != SimulationState.STOPPED || source == null || source == trajectorySource) return;
        this.trajectorySource = source;
        if (source == TrajectorySource.CSV_REPLAY && totalVehicleCount < CSV_VEHICLE_COUNT) {
            totalVehicleCount = CSV_VEHICLE_COUNT;
        }
        rebuildFleet();
        clearTrackedState();
    }

    private void rebuildFleet() {
        int csv = getCsvVehicleCount();
        int generated = totalVehicleCount - csv;
        graphVehicleGenerator = generated > 0
                ? new GraphVehicleGenerator(activeGraph, generated, csv, Config.SEED)
                : null;
    }

    public TrajectorySource getTrajectorySource() {
        return trajectorySource;
    }

    public int getTotalVehicleCount() {
        return totalVehicleCount;
    }

    /** Number of vehicles replayed from the CSV dataset in the current configuration (0 or 20). */
    public int getCsvVehicleCount() {
        return trajectorySource == TrajectorySource.CSV_REPLAY ? Math.min(CSV_VEHICLE_COUNT, totalVehicleCount) : 0;
    }

    public void setStaleThresholdMs(long ms) {
        staleThresholdMs = Math.max(500, Math.min(60_000, ms));
    }

    public long getStaleThresholdMs() {
        return staleThresholdMs;
    }

    // --- Simulation lifecycle ---

    public synchronized void startSimulation() {
        if (shutDown || simulationState == SimulationState.RUNNING) return;
        if (simulationState == SimulationState.STOPPED) {
            // A new run starts from t = 0 with an empty index.
            trajectorySimulator.reset();
            if (graphVehicleGenerator != null) {
                graphVehicleGenerator.reset();
            }
            clearTrackedState();
        }
        simulationState = SimulationState.RUNNING;
        lastRateCheckTime = System.nanoTime();
        updatesSinceLastCheck.set(0);

        if (simulationExecutor == null || simulationExecutor.isShutdown()) {
            simulationExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SimulationTick");
                t.setDaemon(true);
                return t;
            });
        }
        long intervalMs = Math.max(1, (long) (1000.0 / Config.UPDATE_RATE_HZ));
        simulationExecutor.scheduleAtFixedRate(this::simulationTick, 0, intervalMs, TimeUnit.MILLISECONDS);

        if (geofenceExecutor == null || geofenceExecutor.isShutdown()) {
            geofenceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "GeofenceCheck");
                t.setDaemon(true);
                return t;
            });
            geofenceExecutor.scheduleAtFixedRate(this::geofenceCheck, 500, 500, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void pauseSimulation() {
        if (simulationState != SimulationState.RUNNING) return;
        simulationState = SimulationState.PAUSED;
        shutdownExecutor(simulationExecutor);
        simulationExecutor = null;
        measuredUpdateRate = 0.0;
    }

    public synchronized void resumeSimulation() {
        if (simulationState != SimulationState.PAUSED) return;
        startSimulation();
    }

    /** Ends the run. Vehicles stay at their last indexed positions until the next start or a reset. */
    public synchronized void stopSimulation() {
        simulationState = SimulationState.STOPPED;
        shutdownExecutor(simulationExecutor);
        simulationExecutor = null;
        shutdownExecutor(geofenceExecutor);
        geofenceExecutor = null;
        measuredUpdateRate = 0.0;
    }

    /** Stops the run and clears positions, counters and geofence events. */
    public synchronized void resetSimulation() {
        stopSimulation();
        trajectorySimulator.reset();
        if (graphVehicleGenerator != null) {
            graphVehicleGenerator.reset();
        }
        totalUpdates.set(0);
        updatesSinceLastCheck.set(0);
        measuredUpdateRate = 0.0;
        clearTrackedState();
        synchronized (eventHistory) {
            eventHistory.clear();
        }
        enterEvents.set(0);
        exitEvents.set(0);
    }

    private void clearTrackedState() {
        for (IndexerThread indexer : indexers) {
            if (!indexer.clearAndWait(1_000)) {
                System.err.println("Timed out clearing " + indexer.getName());
            }
        }
        motion.clear();
        simClockSec = 0.0;
        geofenceEngine.resetState();
    }

    public void setSimulationSpeed(double multiplier) {
        this.simulationSpeedMultiplier = Math.max(0.1, Math.min(10.0, multiplier));
    }

    private void simulationTick() {
        if (simulationState != SimulationState.RUNNING) return;
        try {
            double effectiveDt = (1.0 / Config.UPDATE_RATE_HZ) * simulationSpeedMultiplier;
            double clock = simClockSec + effectiveDt;
            simClockSec = clock;

            List<PositionUpdate> updates = new ArrayList<>(totalVehicleCount);
            if (getCsvVehicleCount() > 0) {
                updates.addAll(trajectorySimulator.tick(simulationSpeedMultiplier));
            }
            GraphVehicleGenerator generator = graphVehicleGenerator;
            if (generator != null) {
                updates.addAll(generator.tick(effectiveDt));
            }
            for (PositionUpdate update : updates) {
                shardRouter.route(update);
                recordMotion(update, clock);
            }

            totalUpdates.addAndGet(updates.size());
            updatesSinceLastCheck.addAndGet(updates.size());

            long now = System.nanoTime();
            long elapsed = now - lastRateCheckTime;
            if (elapsed >= 1_000_000_000L) {
                long count = updatesSinceLastCheck.getAndSet(0);
                measuredUpdateRate = count * 1_000_000_000.0 / elapsed;
                lastRateCheckTime = now;
            }
        } catch (Exception e) {
            System.err.println("Simulation tick error: " + e.getMessage());
        }
    }

    /**
     * Derives speed and heading from successive positions of one vehicle, using simulated time so the
     * reported speed does not change with the playback multiplier.
     */
    private void recordMotion(PositionUpdate u, double clock) {
        Motion prev = motion.get(u.vehicleId());
        if (prev == null) {
            motion.put(u.vehicleId(), new Motion(u.x(), u.y(), clock, 0.0, -1.0));
            return;
        }
        boolean moved = Math.abs(prev.x() - u.x()) > 1e-9 || Math.abs(prev.y() - u.y()) > 1e-9;
        if (!moved) {
            if (clock - prev.simTime() > 1.0 && prev.speedKmh() != 0.0) {
                motion.put(u.vehicleId(), new Motion(prev.x(), prev.y(), prev.simTime(), 0.0, prev.headingDeg()));
            }
            return;
        }
        double dt = clock - prev.simTime();
        double dist = GeoUtils.haversineMeters(prev.y(), prev.x(), u.y(), u.x());
        double speed = dt > 0 ? dist / dt * 3.6 : prev.speedKmh();
        double heading = GeoUtils.bearingDegrees(prev.y(), prev.x(), u.y(), u.x());
        if (speed > MAX_PLAUSIBLE_SPEED_KMH) {
            // Trajectory loop / teleport: keep the last plausible values.
            speed = prev.speedKmh();
            heading = prev.headingDeg();
        }
        motion.put(u.vehicleId(), new Motion(u.x(), u.y(), clock, speed, heading));
    }

    private void geofenceCheck() {
        try {
            List<ZoneEvent> events = geofenceEngine.check();
            if (!events.isEmpty()) {
                synchronized (eventHistory) {
                    for (ZoneEvent ev : events) {
                        eventHistory.addLast(ev);
                        if (ev.type() == ZoneEvent.EventType.ENTER) enterEvents.incrementAndGet();
                        else exitEvents.incrementAndGet();
                    }
                    while (eventHistory.size() > MAX_EVENT_HISTORY) {
                        eventHistory.removeFirst();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Geofence check error: " + e.getMessage());
        }
    }

    // --- Query methods ---

    private SpatialSnapshot snapshotFor(long vehicleId) {
        return indexers[Math.floorMod(vehicleId, SHARDS)].getPublishedSnapshot();
    }

    private SpatialSnapshot[] currentSnapshots() {
        SpatialSnapshot[] snaps = new SpatialSnapshot[SHARDS];
        for (int i = 0; i < SHARDS; i++) snaps[i] = indexers[i].getPublishedSnapshot();
        return snaps;
    }

    public List<VehicleInfo> getAllVehicles() {
        int count = totalVehicleCount;
        int csv = getCsvVehicleCount();
        List<VehicleInfo> vehicles = new ArrayList<>(count);
        long now = System.currentTimeMillis();
        SimulationState state = simulationState;
        long stale = staleThresholdMs;
        SpatialSnapshot[] snaps = currentSnapshots();

        for (long vid = 0; vid < count; vid++) {
            SpatialSnapshot snapshot = snaps[(int) Math.floorMod(vid, SHARDS)];
            if (snapshot == null) continue;
            Position pos = snapshot.hamt().get(vid);
            if (pos == null) continue;

            Motion m = motion.get(vid);
            double speedKmh = m != null ? m.speedKmh() : 0.0;
            double headingDeg = m != null ? m.headingDeg() : -1.0;

            String status;
            if (state == SimulationState.PAUSED) {
                status = "Paused";
            } else if (state == SimulationState.STOPPED) {
                status = "Idle";
            } else if (now - pos.timestamp() < stale) {
                status = "Active";
            } else {
                status = "Stale";
            }
            String source = vid < csv ? SOURCE_CSV : SOURCE_GENERATED;
            vehicles.add(new VehicleInfo(vid, pos.x(), pos.y(), pos.timestamp(), speedKmh, headingDeg, status, source));
        }
        return vehicles;
    }

    public Position getVehiclePosition(long vehicleId) {
        SpatialSnapshot snapshot = snapshotFor(vehicleId);
        return snapshot == null ? null : snapshot.hamt().get(vehicleId);
    }

    public List<Long> radiusQuery(double centerX, double centerY, double radiusMeters) {
        return radiusQueryVerified(centerX, centerY, radiusMeters).vehicleIds();
    }

    public List<Long> rangeQuery(BoundingBox bbox) {
        List<Long> result = new ArrayList<>();
        for (IndexerThread indexer : indexers) {
            SpatialSnapshot snapshot = indexer.getPublishedSnapshot();
            if (snapshot != null) {
                result.addAll(snapshot.quadtree().rangeQuery(bbox));
            }
        }
        return result;
    }

    /**
     * Radius query through the quadtree (bounding-box pre-filter + exact haversine check), cross-checked
     * against a brute-force scan of the same published snapshots.
     */
    public SpatialQueryResult radiusQueryVerified(double centerX, double centerY, double radiusMeters) {
        SpatialSnapshot[] snaps = currentSnapshots();
        BoundingBox bbox = GeoUtils.radiusBoundingBox(centerX, centerY, radiusMeters);

        long t0 = System.nanoTime();
        int candidates = 0;
        List<Long> result = new ArrayList<>();
        for (SpatialSnapshot snap : snaps) {
            if (snap == null) continue;
            for (long vid : snap.quadtree().rangeQuery(bbox)) {
                candidates++;
                Position pos = snap.hamt().get(vid);
                if (pos != null && GeoUtils.haversineMeters(centerY, centerX, pos.y(), pos.x()) <= radiusMeters) {
                    result.add(vid);
                }
            }
        }
        long indexNanos = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        Set<Long> brute = new HashSet<>();
        int count = totalVehicleCount;
        for (long vid = 0; vid < count; vid++) {
            SpatialSnapshot snap = snaps[(int) Math.floorMod(vid, SHARDS)];
            if (snap == null) continue;
            Position pos = snap.hamt().get(vid);
            if (pos != null && GeoUtils.haversineMeters(centerY, centerX, pos.y(), pos.x()) <= radiusMeters) {
                brute.add(vid);
            }
        }
        long bruteNanos = System.nanoTime() - t1;
        boolean matches = brute.size() == result.size() && brute.containsAll(result);
        Collections.sort(result);
        return new SpatialQueryResult("radius", result, candidates, indexNanos, brute.size(), bruteNanos, matches);
    }

    /** Bounding-box query through the quadtree, cross-checked against a brute-force scan. */
    public SpatialQueryResult rangeQueryVerified(BoundingBox bbox) {
        SpatialSnapshot[] snaps = currentSnapshots();
        long t0 = System.nanoTime();
        List<Long> result = new ArrayList<>();
        for (SpatialSnapshot snap : snaps) {
            if (snap != null) result.addAll(snap.quadtree().rangeQuery(bbox));
        }
        long indexNanos = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        Set<Long> brute = new HashSet<>();
        int count = totalVehicleCount;
        for (long vid = 0; vid < count; vid++) {
            SpatialSnapshot snap = snaps[(int) Math.floorMod(vid, SHARDS)];
            if (snap == null) continue;
            Position pos = snap.hamt().get(vid);
            if (pos != null && bbox.contains(pos.x(), pos.y())) brute.add(vid);
        }
        long bruteNanos = System.nanoTime() - t1;
        boolean matches = brute.size() == result.size() && brute.containsAll(result);
        Collections.sort(result);
        return new SpatialQueryResult("bbox", result, result.size(), indexNanos, brute.size(), bruteNanos, matches);
    }

    public RouteResult findRoute(double startX, double startY, double endX, double endY) {
        RoadGraph.Node startNode = activeGraph.findNearestNode(startX, startY);
        RoadGraph.Node endNode = activeGraph.findNearestNode(endX, endY);
        if (startNode == null || endNode == null) {
            return new RouteResult(List.of(), 0, -1);
        }
        return router.findRoute(-1, startNode.id(), endNode.id());
    }

    /** Snaps both endpoints to the road graph, runs A*, and checks the cost against Dijkstra. */
    public RoutePlan planRoute(double startLng, double startLat, double endLng, double endLat) {
        RoadGraph.Node startNode = activeGraph.findNearestNode(startLng, startLat);
        RoadGraph.Node endNode = activeGraph.findNearestNode(endLng, endLat);
        if (startNode == null || endNode == null) {
            String which = startNode == null && endNode == null ? "Origin and destination are"
                    : startNode == null ? "Origin is" : "Destination is";
            return RoutePlan.failure(which + " more than 500 m from the road network.");
        }
        double originSnap = GeoUtils.haversineMeters(startLat, startLng, startNode.y(), startNode.x());
        double destSnap = GeoUtils.haversineMeters(endLat, endLng, endNode.y(), endNode.x());

        long t0 = System.nanoTime();
        RouteResult route = router.findRoute(-1, startNode.id(), endNode.id());
        long nanos = System.nanoTime() - t0;
        double reference = referenceRouter.shortestCost(startNode.id(), endNode.id());

        if (route.nodeIds().isEmpty()) {
            return RoutePlan.failure("No route: the destination cannot be reached from the origin "
                    + "(some streets in the road graph are one-way).");
        }
        List<double[]> coords = getRouteCoordinates(route);
        double distance = 0;
        for (int i = 0; i + 1 < coords.size(); i++) {
            distance += GeoUtils.haversineMeters(coords.get(i)[1], coords.get(i)[0],
                    coords.get(i + 1)[1], coords.get(i + 1)[0]);
        }
        // Travel time at the posted limits (edges without a known limit count at 40 km/h).
        double seconds = 0;
        List<Long> ids = route.nodeIds();
        for (int i = 0; i + 1 < ids.size(); i++) {
            for (RoadGraph.Edge e : activeGraph.getEdges(ids.get(i))) {
                if (e.toId() == ids.get(i + 1)) {
                    seconds += e.weight() / (e.speedMps() > 0 ? e.speedMps() : 40 / 3.6);
                    break;
                }
            }
        }
        return new RoutePlan(true, "", coords, distance, route.totalCost(), reference, route.nodeIds().size(),
                nanos, originSnap, destSnap,
                new double[]{startNode.x(), startNode.y()}, new double[]{endNode.x(), endNode.y()}, seconds);
    }

    public List<double[]> getRouteCoordinates(RouteResult route) {
        List<double[]> coords = new ArrayList<>();
        for (long nodeId : route.nodeIds()) {
            RoadGraph.Node node = activeGraph.getNode(nodeId);
            if (node != null) {
                coords.add(new double[]{node.x(), node.y()});
            }
        }
        return coords;
    }

    /** Road graph edges as JSON {@code [[[lng,lat],[lng,lat]], ...]}, each undirected edge once. */
    public String getRoadGraphEdgesJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (double[] e : getRoadGraphSegments()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("[[").append(e[0]).append(",").append(e[1]).append("],[")
              .append(e[2]).append(",").append(e[3]).append("]]");
        }
        return sb.append("]").toString();
    }

    /**
     * The road network as polylines {@code {lng0, lat0, lng1, lat1, ...}}: chains of shape points between
     * junctions merged into one line each, which keeps the map payload compact for city-scale graphs.
     */
    public List<double[]> getRoadPolylines() {
        List<double[]> lines = new ArrayList<>();
        if (activeGraph == null) return lines;
        Map<Long, Set<Long>> neighbours = new HashMap<>();
        for (RoadGraph.Node node : activeGraph.getAllNodes()) {
            for (RoadGraph.Edge e : activeGraph.getEdges(node.id())) {
                if (activeGraph.getNode(e.toId()) == null || e.toId() == node.id()) continue;
                neighbours.computeIfAbsent(node.id(), k -> new HashSet<>()).add(e.toId());
                neighbours.computeIfAbsent(e.toId(), k -> new HashSet<>()).add(node.id());
            }
        }
        Set<String> used = new HashSet<>();
        List<Long> starts = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> en : neighbours.entrySet()) if (en.getValue().size() != 2) starts.add(en.getKey());
        starts.addAll(neighbours.keySet()); // pure cycles have no junction; any node starts them
        for (long start : starts) {
            for (long next : neighbours.get(start)) {
                if (!used.add(Math.min(start, next) + ":" + Math.max(start, next))) continue;
                List<Long> chain = new ArrayList<>(List.of(start, next));
                long prev = start, cur = next;
                while (neighbours.get(cur).size() == 2 && cur != start) {
                    long nxt = -1;
                    for (long c : neighbours.get(cur)) if (c != prev) nxt = c;
                    if (nxt < 0 || !used.add(Math.min(cur, nxt) + ":" + Math.max(cur, nxt))) break;
                    chain.add(nxt);
                    prev = cur;
                    cur = nxt;
                }
                double[] line = new double[chain.size() * 2];
                for (int i = 0; i < chain.size(); i++) {
                    RoadGraph.Node n = activeGraph.getNode(chain.get(i));
                    line[2 * i] = n.x();
                    line[2 * i + 1] = n.y();
                }
                lines.add(line);
            }
        }
        return lines;
    }

    /** Number of nodes, directed edges and one-way edges of the routable road network. */
    public RoadNetworkStats getRoadNetworkStats() {
        GraphVehicleGenerator.Network net = GraphVehicleGenerator.network(activeGraph);
        long oneWay = 0;
        double metres = 0;
        for (RoadGraph.Node n : activeGraph.getAllNodes()) {
            for (RoadGraph.Edge e : activeGraph.getEdges(n.id())) {
                boolean reverse = false;
                for (RoadGraph.Edge r : activeGraph.getEdges(e.toId())) {
                    if (r.toId() == n.id()) {
                        reverse = true;
                        break;
                    }
                }
                if (!reverse) oneWay++;
                metres += reverse ? e.weight() / 2 : e.weight();
            }
        }
        return new RoadNetworkStats(net.nodeCount(), net.edgeCount(), oneWay, net.routableNodeCount(), metres / 1000.0);
    }

    /**
     * Demo for geofencing: sends the {@code count} road-graph vehicles nearest to the zone (and outside it)
     * to random drivable destinations inside it. They drive there along normal routes, so the ENTER/EXIT
     * events they cause are ordinary detections; no event or statistic is written directly.
     */
    public synchronized DemoRouting demoRouteVehiclesInto(String zoneId, int count) {
        if (simulationState == SimulationState.STOPPED) {
            return new DemoRouting(List.of(), 0, "Start the simulation first.");
        }
        GraphVehicleGenerator generator = graphVehicleGenerator;
        ZoneView zone = getZones().stream().filter(z -> z.id().equals(zoneId)).findFirst().orElse(null);
        if (zone == null) return new DemoRouting(List.of(), 0, "Unknown zone.");
        if (generator == null) return new DemoRouting(List.of(), 0, "There are no road-graph vehicles to route.");
        List<Position> polygon = zone.polygon();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double cx = 0, cy = 0;
        for (Position p : polygon) {
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
            cx += p.x() / polygon.size();
            cy += p.y() / polygon.size();
        }
        GraphVehicleGenerator.Network net = GraphVehicleGenerator.network(activeGraph);
        List<Long> targets = new ArrayList<>();
        for (RoadGraph.Node n : activeGraph.getAllNodes()) {
            if (n.x() < minX || n.x() > maxX || n.y() < minY || n.y() > maxY) continue;
            if (net.isRoutable(n.id()) && RayCaster.contains(new Position(n.x(), n.y(), 0), polygon)) targets.add(n.id());
        }
        if (targets.isEmpty()) {
            return new DemoRouting(List.of(), 0, "No drivable road lies inside this zone, so no vehicle can enter it.");
        }
        Collections.sort(targets);
        double fx = cx, fy = cy;
        int csv = getCsvVehicleCount();
        List<VehicleInfo> candidates = new ArrayList<>();
        for (VehicleInfo v : getAllVehicles()) {
            if (v.id() >= csv && !RayCaster.contains(new Position(v.x(), v.y(), 0), polygon)) candidates.add(v);
        }
        candidates.sort(Comparator.comparingDouble(v -> GeoUtils.haversineMeters(fy, fx, v.y(), v.x())));
        Random rng = new Random(zoneId.hashCode() * 31L + totalUpdates.get());
        List<Long> routed = new ArrayList<>();
        for (VehicleInfo v : candidates) {
            if (routed.size() >= count) break;
            if (generator.redirect(v.id(), targets.get(rng.nextInt(targets.size())))) routed.add(v.id());
        }
        return new DemoRouting(routed, targets.size(), routed.isEmpty() ? "No vehicle could be routed."
                : "Routing " + routed.size() + " vehicles to " + zone.name() + ".");
    }

    /** Undirected road segments as {@code {lng1, lat1, lng2, lat2}}. */
    public List<double[]> getRoadGraphSegments() {
        List<double[]> segments = new ArrayList<>();
        if (activeGraph == null) return segments;
        Set<String> seen = new HashSet<>();
        for (RoadGraph.Node node : activeGraph.getAllNodes()) {
            for (RoadGraph.Edge edge : activeGraph.getEdges(node.id())) {
                RoadGraph.Node to = activeGraph.getNode(edge.toId());
                if (to == null) continue;
                String key = Math.min(node.id(), to.id()) + "-" + Math.max(node.id(), to.id());
                if (seen.add(key)) {
                    segments.add(new double[]{node.x(), node.y(), to.x(), to.y()});
                }
            }
        }
        return segments;
    }

    // --- Geofences ---

    public GeofenceEngine getGeofenceEngine() {
        return geofenceEngine;
    }

    /** All zones (built-in first, then user zones by creation time) with display names and occupancy. */
    public List<ZoneView> getZones() {
        List<ZoneView> views = new ArrayList<>();
        for (Zone z : geofenceEngine.getBuiltInZones()) {
            views.add(new ZoneView(z.id(), BUILT_IN_ZONE_NAMES.getOrDefault(z.id(), z.id()), true, z.polygon(),
                    GeoUtils.polygonAreaSqMeters(z.polygon()), geofenceEngine.getOccupancy(z.id())));
        }
        List<GeofenceEngine.UserZone> user = new ArrayList<>(geofenceEngine.getAllUserZones());
        user.sort(Comparator.comparingLong(GeofenceEngine.UserZone::createdAt));
        for (GeofenceEngine.UserZone z : user) {
            views.add(new ZoneView(z.zoneId(), z.name(), false, z.polygon(),
                    GeoUtils.polygonAreaSqMeters(z.polygon()), geofenceEngine.getOccupancy(z.zoneId())));
        }
        return views;
    }

    public String zoneDisplayName(String zoneId) {
        String builtIn = BUILT_IN_ZONE_NAMES.get(zoneId);
        if (builtIn != null) return builtIn;
        GeofenceEngine.UserZone z = geofenceEngine.getUserZone(zoneId);
        return z != null ? z.name() : zoneId;
    }

    /** Geofence events, oldest first (bounded to the most recent {@value #MAX_EVENT_HISTORY}). */
    public List<ZoneEvent> getEventHistory() {
        synchronized (eventHistory) {
            return new ArrayList<>(eventHistory);
        }
    }

    /** Total ENTER / EXIT events since the last reset (not bounded by the history size). */
    public long getEnterEventCount() {
        return enterEvents.get();
    }

    public long getExitEventCount() {
        return exitEvents.get();
    }

    // --- Status ---

    public SimulationState getSimulationState() {
        return simulationState;
    }

    public int getActiveVehicleCount() {
        int count = 0;
        for (IndexerThread indexer : indexers) {
            SpatialSnapshot snapshot = indexer.getPublishedSnapshot();
            if (snapshot != null) {
                count += snapshot.hamt().size();
            }
        }
        return count;
    }

    public double getMeasuredUpdateRate() {
        if (simulationState != SimulationState.RUNNING) {
            return 0.0;
        }
        return measuredUpdateRate;
    }

    public long getTotalUpdates() {
        return totalUpdates.get();
    }

    public double getSimulatedSeconds() {
        return simClockSec;
    }

    public IndexStrategy getCurrentStrategy() {
        return currentStrategy;
    }

    /** Root bounds of the spatial indexes (dataset bounds plus a margin). */
    public BoundingBox getGeoBounds() {
        return geoBounds;
    }

    /** Extent of the road graph and CSV area: the region the map should show initially. */
    public BoundingBox getDatasetBounds() {
        return datasetBounds;
    }

    public RoadGraph getActiveGraph() {
        return activeGraph;
    }

    public double getSimulationSpeed() {
        return simulationSpeedMultiplier;
    }

    public IndexerThread[] getIndexers() {
        return indexers;
    }

    /**
     * Collects one metrics sample. Rates are averaged over the samples of the last ~3 seconds, so this is
     * intended to be called periodically (about once per second) by a single monitoring thread. Includes timing one
     * full-extent range query and one 150 m radius query against the live index.
     */
    public synchronized MetricsSample sampleMetrics() {
        long nowNanos = System.nanoTime();
        long indexed = 0;
        long failed = 0;
        int queue = 0;
        long latencyCount = 0;
        double latencyWeighted = 0;
        long latencyMax = 0;
        long newestPublish = 0;
        for (IndexerThread indexer : indexers) {
            indexed += indexer.getUpdatesProcessed();
            failed += indexer.getFailedUpdates();
            queue += indexer.getQueueDepth();
            IndexerThread.LatencyStats stats = indexer.drainLatencyStats();
            latencyCount += stats.count();
            latencyWeighted += stats.meanMs() * stats.count();
            latencyMax = Math.max(latencyMax, stats.maxMs());
            newestPublish = Math.max(newestPublish, indexer.getLastPublishMillis());
        }
        // Generated and indexed rates over the same ~3 s window, so they are directly comparable (a 1 s
        // window aliases with the 200 ms simulation tick and jitters by +/-20%).
        SimulationState state = simulationState;
        long generatedTotal = totalUpdates.get();
        if (state != lastSampleState) rateWindow.clear();
        lastSampleState = state;
        rateWindow.add(new long[]{nowNanos, generatedTotal, indexed});
        while (rateWindow.size() > 2 && nowNanos - rateWindow.get(1)[0] >= RATE_WINDOW_NANOS) {
            rateWindow.remove(0);
        }
        double generatedRate = 0;
        double indexedRate = 0;
        if (state == SimulationState.RUNNING && rateWindow.size() >= 2) {
            long[] first = rateWindow.get(0);
            double seconds = (nowNanos - first[0]) / 1e9;
            if (seconds > 0) {
                generatedRate = Math.max(0, generatedTotal - first[1]) / seconds;
                indexedRate = Math.max(0, indexed - first[2]) / seconds;
            }
        }

        long q0 = System.nanoTime();
        rangeQuery(geoBounds);
        double rangeMs = (System.nanoTime() - q0) / 1e6;

        // Cycle the radius-query centre over a 5x5 grid of the dataset area for a representative mix.
        int cell = sampleCursor++ % 25;
        double cx = datasetBounds.minX() + (datasetBounds.maxX() - datasetBounds.minX()) * (0.1 + 0.2 * (cell % 5));
        double cy = datasetBounds.minY() + (datasetBounds.maxY() - datasetBounds.minY()) * (0.1 + 0.2 * (cell / 5));
        long r0 = System.nanoTime();
        BoundingBox box = GeoUtils.radiusBoundingBox(cx, cy, 150);
        for (IndexerThread indexer : indexers) {
            SpatialSnapshot snap = indexer.getPublishedSnapshot();
            if (snap == null) continue;
            for (long vid : snap.quadtree().rangeQuery(box)) {
                Position p = snap.hamt().get(vid);
                if (p != null) GeoUtils.haversineMeters(cy, cx, p.y(), p.x());
            }
        }
        double radiusMs = (System.nanoTime() - r0) / 1e6;

        Runtime rt = Runtime.getRuntime();
        long committed = rt.totalMemory();
        long used = committed - rt.freeMemory();
        long wall = System.currentTimeMillis();

        return new MetricsSample(wall, state, totalVehicleCount, getActiveVehicleCount(),
                generatedRate, indexedRate, generatedTotal, indexed,
                latencyCount == 0 ? 0 : latencyWeighted / latencyCount, latencyMax, queue,
                newestPublish == 0 ? -1 : wall - newestPublish,
                rangeMs, radiusMs, used, committed, rt.maxMemory(),
                enterEvents.get(), exitEvents.get(), simClockSec, failed);
    }

    // --- Shutdown ---

    public synchronized void shutdown() {
        if (shutDown) return;
        stopSimulation();
        shutDown = true;
        for (IndexerThread indexer : indexers) {
            indexer.shutdown();
        }
    }

    // --- Inner types ---

    public record VehicleInfo(long id, double x, double y, long lastUpdate,
                              double speedKmh, double headingDeg,
                              String status, String source) {}

    private record Motion(double x, double y, double simTime, double speedKmh, double headingDeg) {}

    public record SpatialQueryResult(String kind, List<Long> vehicleIds, int candidates, long indexNanos,
                                     int bruteForceCount, long bruteForceNanos, boolean matchesBruteForce) {}

    public record RoutePlan(boolean found, String message, List<double[]> pathLngLat, double distanceMeters,
                            double astarCost, double dijkstraCost, int nodeCount, long computeNanos,
                            double originSnapMeters, double destinationSnapMeters,
                            double[] originNodeLngLat, double[] destinationNodeLngLat, double travelSeconds) {
        static RoutePlan failure(String message) {
            return new RoutePlan(false, message, List.of(), 0, 0, Double.NaN, 0, 0, 0, 0, null, null, 0);
        }

        public boolean matchesReference() {
            return found && Math.abs(astarCost - dijkstraCost) < 1e-6;
        }
    }

    public record ZoneView(String id, String name, boolean builtIn, List<Position> polygon,
                           double areaSqMeters, int occupancy) {}

    public record RoadNetworkStats(int nodes, int directedEdges, long oneWayEdges, int routableNodes, double roadKm) {}

    public record DemoRouting(List<Long> vehicleIds, int roadNodesInZone, String message) {}

    public record MetricsSample(long wallTimeMillis, SimulationState state, int configuredVehicles,
                                int trackedVehicles, double generatedRate, double indexedRate,
                                long totalGenerated, long totalIndexed, double queueLatencyMeanMs,
                                long queueLatencyMaxMs, int queueDepth, long snapshotAgeMs,
                                double rangeQueryMs, double radiusQueryMs, long heapUsedBytes,
                                long heapCommittedBytes, long heapMaxBytes, long geofenceEnterEvents,
                                long geofenceExitEvents, double simulatedSeconds, long failedUpdates) {}

    private void shutdownExecutor(ExecutorService executor) {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
