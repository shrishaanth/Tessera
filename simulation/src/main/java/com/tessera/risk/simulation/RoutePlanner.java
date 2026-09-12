package com.tessera.risk.simulation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import com.tessera.risk.common.geo.GeoMath;
import com.tessera.risk.common.road.RoadNetwork;

/**
 * Plans a driveable leg between two nodes and pre-computes a speed profile for it.
 *
 * <p>The profile is the <em>lawful, comfortable</em> plan: every segment capped by
 * its real posted limit, tightened at corners, and then smoothed by a backward
 * pass so that a vehicle following it can always decelerate to the next cap within
 * the distance available.
 *
 * <p>That the plan is lawful is what makes unsafe behaviour emerge rather than be
 * injected. A speeding vehicle travels faster than this plan assumes, so when it
 * reaches a corner or a stop the deceleration it needs exceeds the comfortable
 * limit the plan was built around — and that is the hard brake. Speeding causes
 * harsh braking here for the same reason it does on a real road.
 */
final class RoutePlanner {

    /** Speed cap through a sharp turn, metres per second. */
    private static final double V_TURN_SHARP = 3.5;
    /** Speed cap through a moderate turn. */
    private static final double V_TURN_MED = 7.5;
    private static final double TURN_SHARP_DEG = 55.0;
    private static final double TURN_MED_DEG = 28.0;

    private final RoadNetwork network;
    private final double comfortableBrakingMps2;
    private final double maxSpeedMps;

    RoutePlanner(RoadNetwork network, double comfortableBrakingMps2, double maxSpeedMps) {
        this.network = network;
        this.comfortableBrakingMps2 = comfortableBrakingMps2;
        this.maxSpeedMps = maxSpeedMps;
    }

    /**
     * A planned leg: the node path, and per-segment geometry and speed limits,
     * plus the braking-feasible speed cap at each node.
     *
     * @param nodes       node sequence, length n
     * @param edges       edge index of each segment, length n-1
     * @param segLenM     segment length in metres, length n-1
     * @param segLimitMps posted speed limit of each segment, length n-1
     * @param segBearing  bearing along each segment in degrees, length n-1
     * @param nodeCapMps  speed cap at each node after backward smoothing, length n
     */
    record Leg(int[] nodes, int[] edges, double[] segLenM, double[] segLimitMps,
               double[] segBearing, double[] nodeCapMps) {

        int segmentCount() {
            return edges.length;
        }
    }

    /**
     * Plan a leg from {@code from} to {@code to}, or {@code null} when no forward
     * path exists. {@code rnd} decides which signalised intersections are showing
     * red for this particular traversal.
     */
    Leg plan(int from, int to, Random rnd) {
        int[] nodes = shortestPath(from, to);
        if (nodes == null || nodes.length < 2) {
            return null;
        }
        int n = nodes.length;
        int[] edges = new int[n - 1];
        double[] segLenM = new double[n - 1];
        double[] segLimitMps = new double[n - 1];
        double[] segBearing = new double[n - 1];

        for (int i = 0; i < n - 1; i++) {
            int edge = network.edgeBetween(nodes[i], nodes[i + 1]);
            if (edge < 0) {
                return null; // path and adjacency disagree; refuse rather than improvise
            }
            edges[i] = edge;
            segLenM[i] = Math.max(0.1, network.lengthM(edge));
            segLimitMps[i] = Math.min(maxSpeedMps, network.speedLimitKph(edge) / 3.6);
            segBearing[i] = GeoMath.bearingDegrees(
                    network.lat(nodes[i]), network.lon(nodes[i]),
                    network.lat(nodes[i + 1]), network.lon(nodes[i + 1]));
        }

        double[] nodeCap = new double[n];
        nodeCap[0] = 0.0;          // legs start from rest
        nodeCap[n - 1] = 0.0;      // and end at rest
        for (int i = 1; i < n - 1; i++) {
            double turn = Math.abs(angleDelta(segBearing[i - 1], segBearing[i]));
            double cap = turn > TURN_SHARP_DEG ? V_TURN_SHARP
                    : turn > TURN_MED_DEG ? V_TURN_MED
                    : Double.MAX_VALUE;
            if (isSignalised(nodes[i]) && rnd.nextDouble() < 0.30) {
                cap = 0.0; // red light on this pass
            }
            nodeCap[i] = Math.min(cap, Math.min(segLimitMps[i - 1], segLimitMps[i]));
        }

        // Backward pass: a vehicle at nodeCap[i] must be able to reach nodeCap[i+1]
        // using no more than comfortable braking over the segment between them.
        for (int i = n - 2; i >= 0; i--) {
            double reachable = Math.sqrt(
                    nodeCap[i + 1] * nodeCap[i + 1] + 2 * comfortableBrakingMps2 * segLenM[i]);
            nodeCap[i] = Math.min(nodeCap[i], Math.min(reachable, segLimitMps[i]));
        }

        return new Leg(nodes, edges, segLenM, segLimitMps, segBearing, nodeCap);
    }

    /** Deterministic pseudo traffic signal at busier intersections. */
    private boolean isSignalised(int node) {
        int degree = network.fwdRangeEnd(node) - network.fwdRangeStart(node);
        return degree >= 3 && (Long.hashCode(network.osmId(node)) & 7) == 0;
    }

    static double angleDelta(double a, double b) {
        return (b - a + 540.0) % 360.0 - 180.0;
    }

    /** Shortest forward path by travel time, as a node sequence, or null. */
    int[] shortestPath(int src, int dst) {
        if (src == dst) {
            return new int[] {src};
        }
        int n = network.nodeCount();
        double[] dist = new double[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        dist[src] = 0.0;

        PriorityQueue<long[]> queue = new PriorityQueue<>((x, y) ->
                Double.compare(Double.longBitsToDouble(x[0]), Double.longBitsToDouble(y[0])));
        queue.add(new long[] {Double.doubleToLongBits(0.0), src});

        while (!queue.isEmpty()) {
            long[] top = queue.poll();
            double d = Double.longBitsToDouble(top[0]);
            int u = (int) top[1];
            if (d > dist[u]) {
                continue;
            }
            if (u == dst) {
                break;
            }
            for (int e = network.fwdRangeStart(u); e < network.fwdRangeEnd(u); e++) {
                int w = network.fwdTarget(e);
                double nd = d + Math.max(0.05, network.travelSec(e));
                if (nd < dist[w]) {
                    dist[w] = nd;
                    prev[w] = u;
                    queue.add(new long[] {Double.doubleToLongBits(nd), w});
                }
            }
        }
        if (!Double.isFinite(dist[dst])) {
            return null;
        }
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int at = dst; at != -1; at = prev[at]) {
            stack.push(at);
        }
        int[] path = new int[stack.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = stack.pop();
        }
        return path;
    }

    /**
     * Nodes mutually reachable with the network's busiest hub — any two members can
     * therefore route to each other. Restricting depots and stops to this set means
     * a tour can always be closed, so the simulator never needs a fallback that
     * would teleport a vehicle.
     */
    int[] roundTripPool() {
        int hub = busiestNode();
        boolean[] out = reachable(hub, true);
        boolean[] in = reachable(hub, false);
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < network.nodeCount(); i++) {
            if (out[i] && in[i] && network.fwdRangeEnd(i) > network.fwdRangeStart(i)) {
                pool.add(i);
            }
        }
        return pool.stream().mapToInt(Integer::intValue).toArray();
    }

    private int busiestNode() {
        int best = 0;
        int bestDegree = -1;
        for (int n = 0; n < network.nodeCount(); n++) {
            int degree = (network.fwdRangeEnd(n) - network.fwdRangeStart(n))
                    + (network.revRangeEnd(n) - network.revRangeStart(n));
            if (degree > bestDegree) {
                bestDegree = degree;
                best = n;
            }
        }
        return best;
    }

    private boolean[] reachable(int from, boolean forward) {
        boolean[] seen = new boolean[network.nodeCount()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen[from] = true;
        queue.add(from);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            int start = forward ? network.fwdRangeStart(u) : network.revRangeStart(u);
            int end = forward ? network.fwdRangeEnd(u) : network.revRangeEnd(u);
            for (int e = start; e < end; e++) {
                int w = forward ? network.fwdTarget(e) : network.revSource(e);
                if (!seen[w]) {
                    seen[w] = true;
                    queue.add(w);
                }
            }
        }
        return seen;
    }
}
