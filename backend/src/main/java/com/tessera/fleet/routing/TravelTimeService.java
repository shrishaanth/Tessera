package com.tessera.fleet.routing;

import java.util.Arrays;
import java.util.PriorityQueue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Road-network travel-time queries over the OSM {@link RoadGraph} (FR-2.2).
 *
 * <p>The core operation is a single Dijkstra rooted at the job's location,
 * relaxing <em>reverse</em> edges, which yields the drive time <em>into</em> the
 * job from every node. Each candidate vehicle's ETA is then an array lookup.
 * One shortest-path tree per nearest-vehicle request comfortably clears the
 * 1-second budget (FR-2.3 / NFR-1) at this graph size.
 */
@Service
public class TravelTimeService {

    private final RoadGraph graph;

    @Autowired
    public TravelTimeService(RoadGraphLoader loader) {
        this.graph = loader.load();
    }

    /** Test/support constructor: use a pre-built graph. */
    public TravelTimeService(RoadGraph graph) {
        this.graph = graph;
    }

    public RoadGraph graph() {
        return graph;
    }

    /**
     * For a job located nearest to {@code jobNode}, the shortest driving time in
     * seconds from every node to that job node. Unreachable nodes are
     * {@link Double#POSITIVE_INFINITY}.
     */
    public double[] driveTimesToJob(int jobNode) {
        double[] dist = new double[graph.nodeCount()];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        dist[jobNode] = 0.0;

        PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) ->
                Double.compare(Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        queue.add(new long[] {Double.doubleToLongBits(0.0), jobNode});

        while (!queue.isEmpty()) {
            long[] top = queue.poll();
            double d = Double.longBitsToDouble(top[0]);
            int node = (int) top[1];
            if (d > dist[node]) {
                continue;
            }
            for (int e = graph.revRangeStart(node); e < graph.revRangeEnd(node); e++) {
                int prev = graph.revSource(e);
                double nd = d + graph.revTravelSec(e);
                if (nd < dist[prev]) {
                    dist[prev] = nd;
                    queue.add(new long[] {Double.doubleToLongBits(nd), prev});
                }
            }
        }
        return dist;
    }

    /**
     * Travel time in seconds from an arbitrary point to a destination point,
     * snapping both to their nearest graph nodes. A short straight-line
     * "drive to the road" term is added at each end so off-graph points rank
     * sensibly.
     */
    public double travelSecondsBetween(double fromLat, double fromLon,
                                       double toLat, double toLon) {
        int fromNode = graph.nearestNode(fromLat, fromLon);
        int toNode = graph.nearestNode(toLat, toLon);
        double[] times = driveTimesToJob(toNode);
        double onGraph = times[fromNode];
        if (Double.isInfinite(onGraph)) {
            return Double.POSITIVE_INFINITY;
        }
        return snapPenaltySec(fromLat, fromLon, fromNode)
                + onGraph
                + snapPenaltySec(toLat, toLon, toNode);
    }

    /** Great-circle metres from a point to a node, expressed as seconds at ~20 km/h. */
    public double snapPenaltySec(double lat, double lon, int node) {
        double m = GeoMath.haversineMeters(lat, lon, graph.lat(node), graph.lon(node));
        return m / (20.0 / 3.6);
    }
}
