package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;
import com.geotracker.routing.GeoUtils;
import com.geotracker.routing.RoadGraph;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Deterministic, road-constrained vehicle trips on a road graph.
 *
 * <p>Each vehicle starts at a random point of the network (sampled by road length, so vehicles spread
 * evenly over the streets rather than over OSM shape points), picks a destination a few kilometres away,
 * and drives the shortest path there along legal edge directions (one-way streets respected). On arrival
 * it picks the next destination and continues from where it is: movement is continuous, never teleports.
 *
 * <p>Vehicles only use the largest strongly connected component of the graph, so every destination is
 * reachable and no vehicle can be trapped behind a one-way street. Edge speed is the posted limit (if the
 * graph provides one) times a per-vehicle factor; graphs without speeds use a per-vehicle cruise speed.
 * Routes are computed with A* over a compact array copy of the graph; routes needed in the same tick are
 * computed in parallel. Seeded per vehicle, so runs are reproducible.
 */
public class GraphVehicleGenerator {

    /** Shortest edge length used for movement, avoiding division by zero on duplicate nodes. */
    private static final double MIN_EDGE_LENGTH = 0.5;
    /** Route cost = length x clamp(reference speed / edge speed, ...): prefers arterials, keeps A* fast. */
    private static final double COST_REF_SPEED = 40 / 3.6;
    private static final double COST_MIN_FACTOR = 0.75;
    private static final double COST_MAX_FACTOR = 1.5;
    private static final Map<RoadGraph, Network> NETWORKS = Collections.synchronizedMap(new WeakHashMap<>());

    private final Network net;
    private final int vehicleCount;
    private final int baseId;
    private final long seed;
    private Vehicle[] vehicles;
    private long tripsCompleted;

    private static final class Vehicle {
        Random rng;
        double speedFactor;      // share of the speed limit this driver drives at
        double cruiseSpeed;      // m/s on edges without a known limit
        int edge;                // current edge (index into the network arrays)
        double offset;           // metres travelled along the current edge
        int destination;         // node index
        int[] path;              // edges to take after the current edge, ending at the destination
        int pathPos;
        boolean needsRoute;      // destination chosen, path not yet computed
        double x, y;
    }

    public GraphVehicleGenerator(RoadGraph graph, int vehicleCount, int baseId, long seed) {
        this.net = network(graph);
        this.vehicleCount = vehicleCount;
        this.baseId = baseId;
        this.seed = seed;
        initVehicles();
    }

    /** The compact network for a graph (built once per graph instance and cached). */
    public static Network network(RoadGraph graph) {
        synchronized (NETWORKS) {
            return NETWORKS.computeIfAbsent(graph, Network::new);
        }
    }

    private void initVehicles() {
        vehicles = new Vehicle[vehicleCount];
        tripsCompleted = 0;
        for (int i = 0; i < vehicleCount; i++) {
            Vehicle v = new Vehicle();
            v.rng = new Random(seed ^ ((long) (baseId + i) * 2654435761L));
            v.speedFactor = 0.85 + v.rng.nextDouble() * 0.15;
            v.cruiseSpeed = 5.0 + v.rng.nextDouble() * 10.0;   // 18-54 km/h where no limit is known
            v.edge = net.sampleEdge(v.rng);
            v.offset = v.rng.nextDouble() * net.len[v.edge];
            v.destination = net.pickDestination(v.rng, net.to[v.edge]);
            v.needsRoute = true;
            interpolate(v);
            vehicles[i] = v;
        }
    }

    public synchronized List<PositionUpdate> tick(double dtSeconds) {
        long now = System.currentTimeMillis();
        routePending();
        List<PositionUpdate> updates = new ArrayList<>(vehicleCount);
        for (int i = 0; i < vehicleCount; i++) {
            Vehicle v = vehicles[i];
            advance(v, dtSeconds);
            interpolate(v);
            updates.add(new PositionUpdate(baseId + i, v.x, v.y, now));
        }
        return updates;
    }

    /** Computes routes for every vehicle that needs one, in parallel (each vehicle only uses its own state). */
    private void routePending() {
        int pending = 0;
        for (Vehicle v : vehicles) if (v.needsRoute) pending++;
        if (pending == 0) return;
        IntStream range = IntStream.range(0, vehicleCount);
        if (pending > 8) range = range.parallel();
        range.forEach(i -> {
            Vehicle v = vehicles[i];
            if (v.needsRoute) planRoute(v);
        });
    }

    private void planRoute(Vehicle v) {
        int start = net.to[v.edge];
        for (int attempt = 0; attempt < 6; attempt++) {
            if (v.destination != start) {
                int[] path = net.route(start, v.destination);
                if (path != null) {
                    v.path = path;
                    v.pathPos = 0;
                    v.needsRoute = false;
                    return;
                }
            }
            v.destination = net.pickDestination(v.rng, start);
        }
        // Not expected inside a strongly connected component; drive one legal edge and try again next tick.
        int step = -1;
        for (int e = net.firstEdge[start]; e < net.firstEdge[start + 1]; e++) {
            if (net.routable[net.to[e]]) {
                step = e;
                break;
            }
        }
        if (step < 0) return; // isolated node: stay put and retry next tick
        v.path = new int[]{step};
        v.pathPos = 0;
        v.destination = net.to[step];
        v.needsRoute = false;
    }

    private double speedOn(Vehicle v, int edge) {
        double limit = net.speed[edge];
        return (limit > 0 ? limit : v.cruiseSpeed) * v.speedFactor;
    }

    /** Moves a vehicle along its path for dt seconds of simulated time. */
    private void advance(Vehicle v, double dt) {
        double time = dt;
        for (int guard = 0; guard < 10_000 && time > 0; guard++) {
            double speed = speedOn(v, v.edge);
            double remaining = net.len[v.edge] - v.offset;
            if (speed * time < remaining) {
                v.offset += speed * time;
                return;
            }
            time -= remaining / speed;
            v.offset = net.len[v.edge];
            int node = net.to[v.edge];
            if (v.needsRoute || v.path == null || v.pathPos >= v.path.length) {
                // Arrived (or still waiting for a route): stop at the node for the rest of this tick and
                // set off towards the next destination once its route is computed.
                if (!v.needsRoute) {
                    if (node == v.destination) tripsCompleted++;
                    v.destination = net.pickDestination(v.rng, node);
                    v.needsRoute = true;
                }
                return;
            }
            v.edge = v.path[v.pathPos++];
            v.offset = 0;
        }
    }

    private void interpolate(Vehicle v) {
        int a = net.from[v.edge], b = net.to[v.edge];
        double t = Math.max(0, Math.min(1, v.offset / net.len[v.edge]));
        v.x = net.x[a] + (net.x[b] - net.x[a]) * t;
        v.y = net.y[a] + (net.y[b] - net.y[a]) * t;
    }

    public synchronized void reset() {
        initVehicles();
    }

    public int getVehicleCount() {
        return vehicleCount;
    }

    public int getBaseId() {
        return baseId;
    }

    /** Trips that reached their destination since construction or the last reset. */
    public synchronized long getTripsCompleted() {
        return tripsCompleted;
    }

    /**
     * Sends a vehicle to a new destination (used by the geofence demo). It finishes its current road
     * segment and then follows the shortest path to the node, so it never jumps.
     *
     * @return false if the vehicle or node is unknown or the node is not on the routable network
     */
    public synchronized boolean redirect(long vehicleId, long nodeId) {
        int i = (int) (vehicleId - baseId);
        Integer node = net.index.get(nodeId);
        if (i < 0 || i >= vehicleCount || node == null || !net.routable[node]) return false;
        Vehicle v = vehicles[i];
        v.destination = node;
        v.needsRoute = true;
        return true;
    }

    // ---- package-private views for tests

    synchronized long[] currentEdge(int vehicleIndex) {
        Vehicle v = vehicles[vehicleIndex];
        return new long[]{net.ids[net.from[v.edge]], net.ids[net.to[v.edge]]};
    }

    synchronized long destinationNode(int vehicleIndex) {
        return net.ids[vehicles[vehicleIndex].destination];
    }

    synchronized boolean hasRoute(int vehicleIndex) {
        Vehicle v = vehicles[vehicleIndex];
        return !v.needsRoute && v.path != null;
    }

    /** Node ids the vehicle will still pass, from the end of its current edge to its destination. */
    synchronized long[] plannedPathNodes(int vehicleIndex) {
        Vehicle v = vehicles[vehicleIndex];
        if (v.needsRoute || v.path == null) return new long[0];
        int remaining = v.path.length - v.pathPos;
        long[] nodes = new long[remaining + 1];
        nodes[0] = net.ids[net.to[v.edge]];
        for (int k = 0; k < remaining; k++) nodes[k + 1] = net.ids[net.to[v.path[v.pathPos + k]]];
        return nodes;
    }

    /**
     * Array copy of a road graph for fast simulation and routing: CSR adjacency, edge lengths/speeds,
     * local planar coordinates for the A* heuristic, and the largest strongly connected component.
     */
    public static final class Network {
        final long[] ids;
        final double[] x, y;          // lng, lat (or raw x/y for non-geographic graphs)
        final double[] px, py;        // metres in a local plane (heuristic only)
        final int[] firstEdge;        // CSR offsets, size n + 1
        final int[] from, to;         // per edge
        final double[] len, speed, cost;
        final boolean[] routable;     // node in the largest strongly connected component
        final Map<Long, Integer> index;
        private final int[] sampleEdges;
        private final double[] sampleCumulative;
        final double diagonal;
        final double minTrip, maxTrip;
        private final ThreadLocal<Search> search = ThreadLocal.withInitial(() -> new Search(this));

        Network(RoadGraph graph) {
            List<RoadGraph.Node> nodes = new ArrayList<>(graph.getAllNodes());
            nodes.sort(Comparator.comparingLong(RoadGraph.Node::id));
            int n = nodes.size();
            ids = new long[n];
            x = new double[n];
            y = new double[n];
            index = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) {
                RoadGraph.Node node = nodes.get(i);
                ids[i] = node.id();
                x[i] = node.x();
                y[i] = node.y();
                index.put(node.id(), i);
            }
            boolean geo = graph.isGeoGraph();
            px = new double[n];
            py = new double[n];
            double lat0 = 0;
            for (double v : y) lat0 += v;
            lat0 = n == 0 ? 0 : lat0 / n;
            double mPerDeg = Math.toRadians(1) * 6_371_000.0;
            for (int i = 0; i < n; i++) {
                px[i] = geo ? x[i] * mPerDeg * Math.cos(Math.toRadians(lat0)) : x[i];
                py[i] = geo ? y[i] * mPerDeg : y[i];
            }

            List<int[]> edgeList = new ArrayList<>();
            List<double[]> edgeData = new ArrayList<>();
            firstEdge = new int[n + 1];
            for (int i = 0; i < n; i++) {
                firstEdge[i] = edgeList.size();
                for (RoadGraph.Edge e : graph.getEdges(ids[i])) {
                    Integer t = index.get(e.toId());
                    if (t == null || t == i) continue;
                    double length = geo ? GeoUtils.haversineMeters(y[i], x[i], y[t], x[t]) : e.weight();
                    edgeList.add(new int[]{i, t});
                    edgeData.add(new double[]{Math.max(MIN_EDGE_LENGTH, length), e.speedMps()});
                }
            }
            firstEdge[n] = edgeList.size();
            int m = edgeList.size();
            from = new int[m];
            to = new int[m];
            len = new double[m];
            speed = new double[m];
            cost = new double[m];
            for (int e = 0; e < m; e++) {
                from[e] = edgeList.get(e)[0];
                to[e] = edgeList.get(e)[1];
                len[e] = edgeData.get(e)[0];
                speed[e] = edgeData.get(e)[1];
                double factor = speed[e] > 0
                        ? Math.max(COST_MIN_FACTOR, Math.min(COST_MAX_FACTOR, COST_REF_SPEED / speed[e]))
                        : 1.0;
                cost[e] = len[e] * factor;
            }
            routable = largestStronglyConnectedComponent(n);

            // Length-weighted sampling over edges inside the routable component.
            int count = 0;
            for (int e = 0; e < m; e++) if (routable[from[e]] && routable[to[e]]) count++;
            sampleEdges = new int[count];
            sampleCumulative = new double[count];
            double total = 0;
            int k = 0;
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int e = 0; e < m; e++) {
                if (!(routable[from[e]] && routable[to[e]])) continue;
                total += len[e];
                sampleEdges[k] = e;
                sampleCumulative[k++] = total;
                minX = Math.min(minX, px[from[e]]);
                maxX = Math.max(maxX, px[from[e]]);
                minY = Math.min(minY, py[from[e]]);
                maxY = Math.max(maxY, py[from[e]]);
            }
            if (count == 0) throw new IllegalStateException("No traversable edges in graph");
            diagonal = Math.hypot(maxX - minX, maxY - minY);
            minTrip = Math.min(1_500, 0.2 * diagonal);
            maxTrip = Math.max(2 * minTrip, Math.min(7_000, 0.75 * diagonal));
        }

        public int nodeCount() {
            return ids.length;
        }

        public int edgeCount() {
            return to.length;
        }

        public int routableNodeCount() {
            int c = 0;
            for (boolean r : routable) if (r) c++;
            return c;
        }

        public boolean isRoutable(long nodeId) {
            Integer i = index.get(nodeId);
            return i != null && routable[i];
        }

        /** Shortest straight-line trip a vehicle plans (metres), given the network size. */
        public double minTripMeters() {
            return minTrip;
        }

        int sampleEdge(Random rng) {
            double r = rng.nextDouble() * sampleCumulative[sampleCumulative.length - 1];
            int i = Arrays.binarySearch(sampleCumulative, r);
            if (i < 0) i = -i - 1;
            return sampleEdges[Math.min(i, sampleEdges.length - 1)];
        }

        /** A random routable node, sampled by road length, preferably minTrip..maxTrip away from {@code fromNode}. */
        int pickDestination(Random rng, int fromNode) {
            int best = -1;
            double bestMiss = Double.MAX_VALUE;
            for (int attempt = 0; attempt < 40; attempt++) {
                int candidate = to[sampleEdge(rng)];
                if (candidate == fromNode) continue;
                double d = Math.hypot(px[candidate] - px[fromNode], py[candidate] - py[fromNode]);
                if (d >= minTrip && d <= maxTrip) return candidate;
                double miss = d < minTrip ? minTrip - d : d - maxTrip;
                if (miss < bestMiss) {
                    bestMiss = miss;
                    best = candidate;
                }
            }
            return best >= 0 ? best : to[sampleEdge(rng)];
        }

        /** A* from start to goal over routable edges; returns the edge sequence or null if unreachable. */
        int[] route(int start, int goal) {
            return search.get().run(start, goal);
        }

        private double heuristic(int a, int b) {
            // Straight-line metres x the cheapest cost factor, slightly reduced: never overestimates.
            return Math.hypot(px[a] - px[b], py[a] - py[b]) * COST_MIN_FACTOR * 0.995;
        }

        private boolean[] largestStronglyConnectedComponent(int n) {
            // Iterative Kosaraju over CSR adjacency and its reverse.
            int m = to.length;
            int[] revFirst = new int[n + 1];
            for (int e = 0; e < m; e++) revFirst[to[e] + 1]++;
            for (int i = 0; i < n; i++) revFirst[i + 1] += revFirst[i];
            int[] revFrom = new int[m];
            int[] fill = Arrays.copyOf(revFirst, n);
            for (int e = 0; e < m; e++) revFrom[fill[to[e]]++] = from[e];

            int[] order = new int[n];
            int orderSize = 0;
            boolean[] seen = new boolean[n];
            int[] stack = new int[n];
            int[] cursor = new int[n];
            for (int s = 0; s < n; s++) {
                if (seen[s]) continue;
                int sp = 0;
                stack[sp++] = s;
                seen[s] = true;
                cursor[s] = firstEdge[s];
                while (sp > 0) {
                    int u = stack[sp - 1];
                    if (cursor[u] < firstEdge[u + 1]) {
                        int v = to[cursor[u]++];
                        if (!seen[v]) {
                            seen[v] = true;
                            cursor[v] = firstEdge[v];
                            stack[sp++] = v;
                        }
                    } else {
                        sp--;
                        order[orderSize++] = u;
                    }
                }
            }
            int[] comp = new int[n];
            Arrays.fill(comp, -1);
            int bestComp = -1, bestSize = 0, compCount = 0;
            for (int k = orderSize - 1; k >= 0; k--) {
                int s = order[k];
                if (comp[s] >= 0) continue;
                int size = 0, sp = 0;
                stack[sp++] = s;
                comp[s] = compCount;
                while (sp > 0) {
                    int u = stack[--sp];
                    size++;
                    for (int r = revFirst[u]; r < revFirst[u + 1]; r++) {
                        int v = revFrom[r];
                        if (comp[v] < 0) {
                            comp[v] = compCount;
                            stack[sp++] = v;
                        }
                    }
                }
                if (size > bestSize) {
                    bestSize = size;
                    bestComp = compCount;
                }
                compCount++;
            }
            boolean[] inMain = new boolean[n];
            for (int i = 0; i < n; i++) inMain[i] = comp[i] == bestComp;
            return inMain;
        }
    }

    /** Reusable A* state (one per thread). Uses visit stamps so nothing is cleared between searches. */
    private static final class Search {
        private final Network net;
        private final double[] g;
        private final int[] prevEdge;
        private final int[] seenStamp;
        private final int[] closedStamp;
        private int stamp = 0;
        private int[] heapNode = new int[1024];
        private double[] heapKey = new double[1024];
        private int heapSize;

        Search(Network net) {
            this.net = net;
            int n = net.ids.length;
            g = new double[n];
            prevEdge = new int[n];
            seenStamp = new int[n];
            closedStamp = new int[n];
        }

        int[] run(int start, int goal) {
            if (++stamp == Integer.MAX_VALUE) {
                Arrays.fill(seenStamp, 0);
                Arrays.fill(closedStamp, 0);
                stamp = 1;
            }
            heapSize = 0;
            g[start] = 0;
            prevEdge[start] = -1;
            seenStamp[start] = stamp;
            push(start, net.heuristic(start, goal));
            boolean found = false;
            while (heapSize > 0) {
                int u = heapNode[0];
                pop();
                if (closedStamp[u] == stamp) continue;
                closedStamp[u] = stamp;
                if (u == goal) {
                    found = true;
                    break;
                }
                double gu = g[u];
                for (int e = net.firstEdge[u]; e < net.firstEdge[u + 1]; e++) {
                    int v = net.to[e];
                    if (!net.routable[v] || closedStamp[v] == stamp) continue;
                    double ng = gu + net.cost[e];
                    if (seenStamp[v] != stamp || ng < g[v]) {
                        seenStamp[v] = stamp;
                        g[v] = ng;
                        prevEdge[v] = e;
                        push(v, ng + net.heuristic(v, goal));
                    }
                }
            }
            if (!found) return null;
            int length = 0;
            for (int node = goal; node != start; node = net.from[prevEdge[node]]) length++;
            int[] path = new int[length];
            for (int node = goal, k = length - 1; node != start; node = net.from[prevEdge[node]]) path[k--] = prevEdge[node];
            return path;
        }

        private void push(int node, double key) {
            if (heapSize == heapNode.length) {
                heapNode = Arrays.copyOf(heapNode, heapSize * 2);
                heapKey = Arrays.copyOf(heapKey, heapSize * 2);
            }
            int i = heapSize++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (heapKey[parent] <= key) break;
                heapNode[i] = heapNode[parent];
                heapKey[i] = heapKey[parent];
                i = parent;
            }
            heapNode[i] = node;
            heapKey[i] = key;
        }

        private void pop() {
            int lastNode = heapNode[--heapSize];
            double lastKey = heapKey[heapSize];
            int i = 0;
            while (true) {
                int c = 2 * i + 1;
                if (c >= heapSize) break;
                if (c + 1 < heapSize && heapKey[c + 1] < heapKey[c]) c++;
                if (heapKey[c] >= lastKey) break;
                heapNode[i] = heapNode[c];
                heapKey[i] = heapKey[c];
                i = c;
            }
            heapNode[i] = lastNode;
            heapKey[i] = lastKey;
        }
    }
}
