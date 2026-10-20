package com.geotracker.service;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.routing.GeoUtils;
import com.geotracker.routing.RoadGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for backend defects found while rebuilding the desktop UI. */
class ApplicationServiceRegressionTest {

    private ApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationService();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private void runFor(long millis) throws InterruptedException {
        service.startSimulation();
        Thread.sleep(millis);
    }

    @Test
    void indexBoundsCoverTheWholeRoadGraph() {
        // The quadtree silently drops points outside its root bounds; the OSM graph extends beyond
        // Config's nominal area, so the index bounds must be derived from the graph.
        BoundingBox bounds = service.getGeoBounds();
        for (RoadGraph.Node n : service.getActiveGraph().getAllNodes()) {
            assertTrue(bounds.contains(n.x(), n.y()), "Road node outside index bounds: " + n);
        }
    }

    @Test
    @Timeout(20)
    void everyTrackedVehicleIsFoundByAFullExtentRangeQuery() throws InterruptedException {
        service.setVehicleCount(1_000);
        runFor(2_000);
        service.pauseSimulation();
        Thread.sleep(200); // let the indexers publish the last updates
        int tracked = service.getActiveVehicleCount();
        assertEquals(1_000, tracked);
        assertEquals(tracked, service.rangeQuery(service.getGeoBounds()).size(),
                "Vehicles in the HAMT but missing from the quadtree");
    }

    /** Fastest posted limit in the road graph (km/h); vehicles drive at 85-100% of the edge limit. */
    private double fastestLimitKmh() {
        double max = 0;
        for (RoadGraph.Node n : service.getActiveGraph().getAllNodes()) {
            for (RoadGraph.Edge e : service.getActiveGraph().getEdges(n.id())) max = Math.max(max, e.speedMps() * 3.6);
        }
        return max > 0 ? max : 54.0;
    }

    @Test
    @Timeout(20)
    void generatedSpeedsArePhysicallyPlausible() throws InterruptedException {
        // Vehicles drive at most the posted limit (97 km/h on I-5). Before the fix, distance left over at a
        // node was carried onto the next edge as a fraction of its length, producing jumps of 100+ km/h on
        // 40 km/h streets; reported speed can never exceed the fastest limit.
        service.setVehicleCount(1_000);
        runFor(3_000);
        List<ApplicationService.VehicleInfo> vehicles = service.getAllVehicles();
        assertFalse(vehicles.isEmpty());
        double max = vehicles.stream().mapToDouble(ApplicationService.VehicleInfo::speedKmh).max().orElse(0);
        assertTrue(max <= fastestLimitKmh() + 0.5, "Implausible speed " + max + " km/h");
        long moving = vehicles.stream().filter(v -> v.speedKmh() > 1).count();
        assertTrue(moving > 900, "Expected most vehicles to be moving, got " + moving);
    }

    @Test
    @Timeout(20)
    void speedDoesNotScaleWithPlaybackMultiplier() throws InterruptedException {
        service.setVehicleCount(100);
        service.setSimulationSpeed(5.0);
        runFor(2_000);
        double max = service.getAllVehicles().stream().mapToDouble(ApplicationService.VehicleInfo::speedKmh).max().orElse(0);
        // At 5x playback a wall-clock speed would be up to 5x the limit.
        assertTrue(max > 10 && max <= fastestLimitKmh() + 0.5, "Speed should be reported in simulated time, got " + max);
    }

    @Test
    @Timeout(20)
    void headingMatchesDirectionOfTravel() throws InterruptedException {
        service.setVehicleCount(50);
        runFor(1_500);
        Map<Long, Position> before = new HashMap<>();
        for (var v : service.getAllVehicles()) before.put(v.id(), new Position(v.x(), v.y(), 0));
        Thread.sleep(220);
        int checked = 0;
        for (var v : service.getAllVehicles()) {
            Position p = before.get(v.id());
            if (p == null || v.headingDeg() < 0) continue;
            if (GeoUtils.haversineMeters(p.y(), p.x(), v.y(), v.x()) < 1.0) continue;
            double expected = GeoUtils.bearingDegrees(p.y(), p.x(), v.y(), v.x());
            double diff = Math.abs(((expected - v.headingDeg()) + 540) % 360 - 180);
            // Vehicles may turn at an intersection between the two samples.
            if (diff < 30) checked++;
        }
        assertTrue(checked > 25, "Heading disagreed with movement for most vehicles (" + checked + " agreed)");
    }

    @Test
    @Timeout(20)
    void shrinkingTheFleetRemovesVehiclesFromTheIndex() throws InterruptedException {
        service.setVehicleCount(1_000);
        runFor(1_500);
        service.stopSimulation();
        service.setVehicleCount(100);
        assertEquals(0, service.getActiveVehicleCount(), "Changing the fleet starts from an empty index");
        runFor(1_500);
        service.pauseSimulation();
        Thread.sleep(200);
        assertEquals(100, service.getActiveVehicleCount());
        assertEquals(100, service.rangeQuery(service.getGeoBounds()).size(), "Ghost vehicles from the old fleet");
    }

    @Test
    @Timeout(20)
    void resetClearsPositionsCountersAndEvents() throws InterruptedException {
        service.setVehicleCount(1_000);
        runFor(2_000);
        service.resetSimulation();
        assertEquals(ApplicationService.SimulationState.STOPPED, service.getSimulationState());
        assertEquals(0, service.getActiveVehicleCount());
        assertEquals(0, service.getTotalUpdates());
        assertTrue(service.getEventHistory().isEmpty());
        assertEquals(0, service.getEnterEventCount() + service.getExitEventCount());
        assertTrue(service.getAllVehicles().isEmpty());
    }

    @Test
    @Timeout(20)
    void pauseFreezesPositionsAndResumeContinues() throws InterruptedException {
        service.setVehicleCount(100);
        runFor(1_500);
        service.pauseSimulation();
        Thread.sleep(150);
        Map<Long, Position> frozen = new HashMap<>();
        for (var v : service.getAllVehicles()) {
            assertEquals("Paused", v.status());
            frozen.put(v.id(), new Position(v.x(), v.y(), v.lastUpdate()));
        }
        Thread.sleep(600);
        for (var v : service.getAllVehicles()) {
            Position p = frozen.get(v.id());
            assertEquals(p.x(), v.x(), 0.0);
            assertEquals(p.y(), v.y(), 0.0);
        }
        long updates = service.getTotalUpdates();
        service.resumeSimulation();
        Thread.sleep(600);
        assertTrue(service.getTotalUpdates() > updates, "Resume should continue producing updates");
        assertTrue(service.getAllVehicles().stream().allMatch(v -> v.status().equals("Active")));
    }

    @Test
    @Timeout(20)
    void stopKeepsLastPositionsAsIdle() throws InterruptedException {
        service.setVehicleCount(100);
        runFor(1_200);
        service.stopSimulation();
        Thread.sleep(100);
        List<ApplicationService.VehicleInfo> vehicles = service.getAllVehicles();
        assertEquals(100, vehicles.size());
        assertTrue(vehicles.stream().allMatch(v -> v.status().equals("Idle")));
    }

    @Test
    @Timeout(30)
    void radiusAndBoxQueriesMatchBruteForceWhileVehiclesMove() throws InterruptedException {
        service.setVehicleCount(1_000);
        runFor(1_500);
        BoundingBox d = service.getDatasetBounds();
        Random rng = new Random(7);
        for (int i = 0; i < 40; i++) {
            double lng = d.minX() + rng.nextDouble() * (d.maxX() - d.minX());
            double lat = d.minY() + rng.nextDouble() * (d.maxY() - d.minY());
            double radius = 20 + rng.nextDouble() * 300;
            var r = service.radiusQueryVerified(lng, lat, radius);
            assertTrue(r.matchesBruteForce(), "Radius query mismatch at " + lat + "," + lng + " r=" + radius);
            double w = 0.0005 + rng.nextDouble() * 0.002, h = 0.0005 + rng.nextDouble() * 0.002;
            var b = service.rangeQueryVerified(new BoundingBox(lng - w, lat - h, lng + w, lat + h));
            assertTrue(b.matchesBruteForce(), "Box query mismatch at " + lat + "," + lng);
        }
    }

    @Test
    @Timeout(20)
    void radiusQueryIncludesVehiclesRightAtTheEdge() throws InterruptedException {
        // The bounding-box pre-filter used 111,320 m/degree while the distance check uses the haversine
        // sphere (~111,195 m/degree), so points just inside the radius due north/south were dropped.
        service.setVehicleCount(1_000);
        runFor(1_200);
        service.pauseSimulation();
        Thread.sleep(150);
        Position target = service.getVehiclePosition(10);
        assertNotNull(target);
        double centerLat = target.y() - 0.001; // due south of the vehicle
        double dist = GeoUtils.haversineMeters(centerLat, target.x(), target.y(), target.x());
        List<Long> hits = service.radiusQuery(target.x(), centerLat, dist * 1.0005);
        assertTrue(hits.contains(10L), "Vehicle at " + dist + " m due north must be inside a " + (dist * 1.0005) + " m radius");
    }

    @Test
    void routePlansAreOptimalAgainstDijkstra() {
        List<RoadGraph.Node> nodes = new ArrayList<>(service.getActiveGraph().getAllNodes());
        nodes.sort(Comparator.comparingLong(RoadGraph.Node::id));
        Random rng = new Random(11);
        int found = 0;
        for (int i = 0; i < 60; i++) {
            RoadGraph.Node a = nodes.get(rng.nextInt(nodes.size()));
            RoadGraph.Node b = nodes.get(rng.nextInt(nodes.size()));
            var plan = service.planRoute(a.x(), a.y(), b.x(), b.y());
            if (!plan.found()) continue;
            found++;
            assertTrue(plan.matchesReference(), "A* cost " + plan.astarCost() + " vs Dijkstra " + plan.dijkstraCost());
            // Edge weights are haversine metres, stored rounded to the centimetre.
            assertEquals(plan.astarCost(), plan.distanceMeters(), 0.005 * plan.nodeCount() + 1e-6, "Edge weights are haversine metres");
        }
        assertTrue(found > 40, "Most random node pairs should be connected, got " + found);
    }

    @Test
    void routePlanRejectsPointsFarFromTheNetwork() {
        var plan = service.planRoute(-122.0, 47.0, -122.3320, 47.6480);
        assertFalse(plan.found());
        assertTrue(plan.message().contains("500 m"));
    }

    @Test
    @Timeout(20)
    void sampledRatesAgreeWithTheConfiguredFleet() throws InterruptedException {
        service.setVehicleCount(100);
        service.startSimulation();
        ApplicationService.MetricsSample m = null;
        for (int i = 0; i < 5; i++) {
            Thread.sleep(1_000);
            m = service.sampleMetrics();
        }
        // 100 vehicles at 5 Hz = 500 updates/s generated; the indexers must keep up.
        assertEquals(500, m.generatedRate(), 60);
        assertEquals(m.generatedRate(), m.indexedRate(), 60);
        service.pauseSimulation();
        assertEquals(0, service.sampleMetrics().generatedRate(), 0.0);
    }

    @Test
    void trajectorySourceCannotChangeWhileRunning() {
        service.startSimulation();
        service.setTrajectorySource(ApplicationService.TrajectorySource.CSV_REPLAY);
        assertEquals(ApplicationService.TrajectorySource.ROAD_GRAPH, service.getTrajectorySource());
        service.stopSimulation();
        service.setTrajectorySource(ApplicationService.TrajectorySource.CSV_REPLAY);
        assertEquals(ApplicationService.TrajectorySource.CSV_REPLAY, service.getTrajectorySource());
    }

    @Test
    @Timeout(20)
    void csvReplayVehiclesAreLabelledCsv() throws InterruptedException {
        service.setTrajectorySource(ApplicationService.TrajectorySource.CSV_REPLAY);
        service.setVehicleCount(100);
        runFor(1_200);
        for (var v : service.getAllVehicles()) {
            assertEquals(v.id() < 20 ? ApplicationService.SOURCE_CSV : ApplicationService.SOURCE_GENERATED, v.source());
        }
    }

    @Test
    void builtInZonesAreExposedForDisplay() {
        var zones = service.getZones();
        assertTrue(zones.size() >= 2);
        assertTrue(zones.stream().anyMatch(z -> z.builtIn() && z.id().equals("gasworks_park")));
        assertTrue(zones.stream().allMatch(z -> z.areaSqMeters() > 0));
    }
}
