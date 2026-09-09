package com.tessera.fleet.ingestion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.Consumer;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.routing.GeoMath;
import com.tessera.fleet.routing.RoadGraph;

/**
 * A deterministic vehicle-position feed that moves synthetic vehicles along real
 * OSM road edges. Used for local development and by every automated test, so the
 * suite never depends on an external feed.
 *
 * <p>This is <em>not</em> real position data and is disclosed as such (FR-7.2):
 * {@link #isSubstitute()} is {@code true} and {@link #disclosure()} says plainly
 * that the positions are simulated.
 *
 * <p>Motion has two modes. Unassigned vehicles <em>roam</em> — at each node they
 * pick a random outgoing edge. A vehicle given a destination via
 * {@link #assignDestination} <em>routes</em> — it follows a shortest road-network
 * path there, fires the arrival callback, and then resumes roaming. This is what
 * makes an assigned vehicle actually drive to its job (FR-2) rather than wander.
 *
 * <p>Determinism: all roaming randomness comes from a single seeded
 * {@link Random}, and motion is a pure function of the elapsed-time argument to
 * {@link #advance(long)}. With no destinations assigned the RNG draw sequence is
 * unchanged, so existing golden-path tests stay stable. {@link #poll()} feeds
 * wall-clock elapsed time; tests call {@link #advance(long)} directly.
 */
public class SimulatedPositionSource implements PositionSource {

    private static final String[] DRIVER_NAMES = {
            "A. Okafor", "B. Nguyen", "C. Delgado", "D. Petrov", "E. Haddad",
            "F. Larsson", "G. Ibrahim", "H. Kowalski", "I. Santos", "J. Meyer",
            "K. Abebe", "L. Romano", "M. Fernandez", "N. Dubois", "O. Yamamoto",
            "P. Novak", "Q. Rahman", "R. Andersson", "S. Kaur", "T. Bauer",
            "U. Costa", "V. Popescu", "W. Sørensen", "X. Fisher", "Y. Kovac",
            "Z. Moreau", "A. Bianchi", "B. Schmidt", "C. Walsh", "D. Fournier"
    };

    private final RoadGraph graph;
    private final int vehicleCount;
    private final Random random;
    private final Consumer<String> onArrival;
    private final List<SimVehicle> vehicles = new ArrayList<>();
    private final Map<String, SimVehicle> byId = new HashMap<>();
    private long lastPollEpochMs = 0L;

    public SimulatedPositionSource(RoadGraph graph, FleetProperties.Simulator config) {
        this(graph, config, id -> { });
    }

    /**
     * @param onArrival invoked (with the vehicle id) on the tick a routed vehicle
     *        reaches its assigned destination. Used to complete the job.
     */
    public SimulatedPositionSource(RoadGraph graph, FleetProperties.Simulator config,
                                   Consumer<String> onArrival) {
        this.graph = graph;
        this.vehicleCount = Math.max(0, config.vehicleCount());
        this.random = new Random(config.seed());
        this.onArrival = onArrival != null ? onArrival : id -> { };
        spawn();
    }

    private void spawn() {
        for (int i = 0; i < vehicleCount; i++) {
            String id = String.format("SIM-%03d", i + 1);
            String driver = DRIVER_NAMES[i % DRIVER_NAMES.length];
            int node = randomNodeWithOutEdge();
            SimVehicle v = new SimVehicle(id, driver, node);
            pickNextEdge(v, -1);
            vehicles.add(v);
            byId.put(id, v);
        }
    }

    private int randomNodeWithOutEdge() {
        for (int attempt = 0; attempt < 64; attempt++) {
            int n = random.nextInt(graph.nodeCount());
            if (graph.fwdRangeEnd(n) > graph.fwdRangeStart(n)) {
                return n;
            }
        }
        return 0;
    }

    /**
     * Route a simulated vehicle to the destination (snapped to the nearest graph
     * node). Safe to call from any thread — the request is a single volatile
     * write, picked up on the next {@link #advance} tick. No-op for an unknown id.
     */
    public void assignDestination(String vehicleId, double lat, double lon) {
        SimVehicle v = byId.get(vehicleId);
        if (v == null) {
            return;
        }
        v.pendingDestNode = graph.nearestNode(lat, lon);
    }

    /**
     * Advance every vehicle by {@code elapsedMillis} of simulated time and return
     * a fresh position report for each. Pure w.r.t. the argument and RNG state.
     */
    public List<PositionReport> advance(long elapsedMillis) {
        double dtSec = Math.max(0, elapsedMillis) / 1000.0;
        long now = System.currentTimeMillis();
        List<PositionReport> reports = new ArrayList<>(vehicles.size());
        List<String> arrivedThisTick = null;
        for (SimVehicle v : vehicles) {
            applyPendingDestination(v);
            step(v, dtSec);
            if (v.justArrived) {
                v.justArrived = false;
                if (arrivedThisTick == null) {
                    arrivedThisTick = new ArrayList<>();
                }
                arrivedThisTick.add(v.id);
            }
            double f = v.edgeLenM <= 0 ? 0.0 : v.progressM / v.edgeLenM;
            double lat = lerp(graph.lat(v.fromNode), graph.lat(v.toNode), f);
            double lon = lerp(graph.lon(v.fromNode), graph.lon(v.toNode), f);
            double heading = GeoMath.bearingDegrees(
                    graph.lat(v.fromNode), graph.lon(v.fromNode),
                    graph.lat(v.toNode), graph.lon(v.toNode));
            reports.add(new PositionReport(v.id, v.driver, lat, lon,
                    heading, v.edgeSpeedMps * 3.6, now));
        }
        if (arrivedThisTick != null) {
            for (String id : arrivedThisTick) {
                onArrival.accept(id);
            }
        }
        return reports;
    }

    private void applyPendingDestination(SimVehicle v) {
        int dest = v.pendingDestNode;
        if (dest < 0) {
            return;
        }
        v.pendingDestNode = -1;
        // Plan from the node the vehicle is currently committed to reaching, so
        // it finishes the edge it is on and then picks up the route.
        int[] path = routeToward(v.toNode, dest);
        if (path != null) {
            v.routeNodes = path;
            v.routeCursor = 0;
        }
    }

    private void step(SimVehicle v, double dtSec) {
        double move = v.edgeSpeedMps * dtSec;
        int guard = 0;
        while (move > 0 && guard++ < 512) {
            double remainingOnEdge = v.edgeLenM - v.progressM;
            if (move < remainingOnEdge) {
                v.progressM += move;
                return;
            }
            move -= remainingOnEdge;
            int arrived = v.toNode;
            int cameFrom = v.fromNode;
            v.fromNode = arrived;
            pickNextEdge(v, cameFrom);
            v.progressM = 0;
            if (v.edgeLenM <= 0) {
                return;
            }
        }
    }

    private void pickNextEdge(SimVehicle v, int avoidNode) {
        // Routing mode takes precedence over roaming.
        if (v.routeNodes != null && advanceRoute(v)) {
            return;
        }

        int start = graph.fwdRangeStart(v.fromNode);
        int end = graph.fwdRangeEnd(v.fromNode);
        if (end <= start) {
            // Directed dead-end (a one-way street, or an edge clipped at the
            // demo-area border). Back out the way we came so the path stays
            // physically continuous; only if the node is genuinely isolated fall
            // back to the *nearest* drivable node — never a random one, which is
            // what used to fling vehicles across the map.
            if (backOutViaReverseEdge(v)) {
                return;
            }
            v.fromNode = nearestDrivableNode(v.fromNode);
            start = graph.fwdRangeStart(v.fromNode);
            end = graph.fwdRangeEnd(v.fromNode);
            if (end <= start) {
                return;
            }
        }

        int chosen = -1;
        int options = end - start;
        int offset = random.nextInt(options);
        for (int k = 0; k < options; k++) {
            int e = start + (offset + k) % options;
            if (graph.fwdTarget(e) != avoidNode) {
                chosen = e;
                break;
            }
        }
        if (chosen < 0) {
            chosen = start + offset; // only way out is back the way we came
        }
        setEdge(v, graph.fwdTarget(chosen), graph.fwdTravelSec(chosen));
    }

    /**
     * Follow the planned path by one hop. Returns {@code true} when the next road
     * edge was taken from the route; {@code false} (route cleared) when the
     * destination is reached — {@link SimVehicle#justArrived} is set — or the
     * path can no longer be followed, so the caller resumes roaming.
     */
    private boolean advanceRoute(SimVehicle v) {
        int[] r = v.routeNodes;
        int idx = indexOf(r, v.fromNode, v.routeCursor);
        if (idx < 0 || idx >= r.length - 1) {
            boolean reachedEnd = idx == r.length - 1;
            v.routeNodes = null;
            v.routeCursor = 0;
            if (reachedEnd) {
                v.justArrived = true;
            }
            return false;
        }
        v.routeCursor = idx;
        int next = r[idx + 1];
        int edge = forwardEdgeTo(v.fromNode, next);
        if (edge < 0) {
            v.routeNodes = null;
            v.routeCursor = 0;
            return false;
        }
        setEdge(v, next, graph.fwdTravelSec(edge));
        return true;
    }

    /** Drive back down an in-edge of a directed dead-end. */
    private boolean backOutViaReverseEdge(SimVehicle v) {
        int rs = graph.revRangeStart(v.fromNode);
        int re = graph.revRangeEnd(v.fromNode);
        if (re <= rs) {
            return false;
        }
        int e = rs + random.nextInt(re - rs);
        setEdge(v, graph.revSource(e), graph.revTravelSec(e));
        return true;
    }

    private void setEdge(SimVehicle v, int toNode, double travelSec) {
        v.toNode = toNode;
        v.edgeLenM = GeoMath.haversineMeters(
                graph.lat(v.fromNode), graph.lon(v.fromNode),
                graph.lat(toNode), graph.lon(toNode));
        v.edgeSpeedMps = travelSec > 0.01 ? v.edgeLenM / travelSec : 8.0;
        if (v.edgeSpeedMps < 1.0) {
            v.edgeSpeedMps = 1.0;
        }
    }

    private int forwardEdgeTo(int fromNode, int toNode) {
        for (int e = graph.fwdRangeStart(fromNode); e < graph.fwdRangeEnd(fromNode); e++) {
            if (graph.fwdTarget(e) == toNode) {
                return e;
            }
        }
        return -1;
    }

    private int nearestDrivableNode(int fromNode) {
        double flat = graph.lat(fromNode);
        double flon = graph.lon(fromNode);
        int best = fromNode;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < graph.nodeCount(); i++) {
            if (i == fromNode || graph.fwdRangeEnd(i) <= graph.fwdRangeStart(i)) {
                continue;
            }
            double d = GeoMath.haversineMeters(flat, flon, graph.lat(i), graph.lon(i));
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * A forward path (inclusive node-id sequence) from {@code src} toward
     * {@code dst}. If {@code dst} is reachable, the shortest path to it; if it is
     * not (its nearest-node snap landed in a disconnected pocket, or across a
     * one-way boundary), the shortest path to the reachable node physically
     * closest to it — so a job whose destination snaps somewhere awkward still
     * resolves instead of leaving the vehicle roaming forever. {@code null} only
     * when {@code src} has no outgoing route at all.
     */
    private int[] routeToward(int src, int dst) {
        int n = graph.nodeCount();
        double[] dist = new double[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        dist[src] = 0.0;

        PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) ->
                Double.compare(Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        queue.add(new long[] {Double.doubleToLongBits(0.0), src});

        while (!queue.isEmpty()) {
            long[] top = queue.poll();
            double d = Double.longBitsToDouble(top[0]);
            int u = (int) top[1];
            if (d > dist[u]) {
                continue;
            }
            for (int e = graph.fwdRangeStart(u); e < graph.fwdRangeEnd(u); e++) {
                int w = graph.fwdTarget(e);
                double nd = d + Math.max(0.1, graph.fwdTravelSec(e));
                if (nd < dist[w]) {
                    dist[w] = nd;
                    prev[w] = u;
                    queue.add(new long[] {Double.doubleToLongBits(nd), w});
                }
            }
        }

        int target = dst;
        if (!Double.isFinite(dist[dst])) {
            target = -1;
            double best = Double.MAX_VALUE;
            double dlat = graph.lat(dst);
            double dlon = graph.lon(dst);
            for (int i = 0; i < n; i++) {
                if (!Double.isFinite(dist[i])) {
                    continue;
                }
                double m = GeoMath.haversineMeters(dlat, dlon, graph.lat(i), graph.lon(i));
                if (m < best) {
                    best = m;
                    target = i;
                }
            }
            if (target < 0) {
                return null;
            }
        }
        if (target == src) {
            return new int[] {src};
        }
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int at = target; at != -1; at = prev[at]) {
            stack.push(at);
        }
        int[] path = new int[stack.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = stack.pop();
        }
        return path;
    }

    private static int indexOf(int[] a, int value, int from) {
        for (int i = Math.max(0, from); i < a.length; i++) {
            if (a[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    // ------------------------------------------------------------ PositionSource

    @Override
    public String id() {
        return "simulator";
    }

    @Override
    public String displayName() {
        return "Built-in vehicle simulator";
    }

    @Override
    public boolean isSubstitute() {
        return true;
    }

    @Override
    public String disclosure() {
        return "Positions are SIMULATED, not real. Synthetic vehicles are moved "
                + "along a real OpenStreetMap road network for the demo area. "
                + "No physical vehicles or telematics feed are involved.";
    }

    @Override
    public List<PositionReport> poll() {
        long now = System.currentTimeMillis();
        long elapsed = lastPollEpochMs == 0 ? 1000L : now - lastPollEpochMs;
        lastPollEpochMs = now;
        return advance(elapsed);
    }

    public int vehicleCount() {
        return vehicleCount;
    }

    private static final class SimVehicle {
        final String id;
        final String driver;
        int fromNode;
        int toNode;
        double edgeLenM;
        double edgeSpeedMps;
        double progressM;

        /** Nearest-node index requested by assignDestination; -1 when none pending. */
        volatile int pendingDestNode = -1;
        /** Planned node path while routing, or null while roaming. */
        int[] routeNodes;
        int routeCursor;
        /** Set on the tick the planned destination is reached; consumed by advance(). */
        boolean justArrived;

        SimVehicle(String id, String driver, int startNode) {
            this.id = id;
            this.driver = driver;
            this.fromNode = startNode;
            this.toNode = startNode;
        }
    }
}
