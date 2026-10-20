package com.geotracker.routing;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Plain Dijkstra shortest-path cost, used as an independent reference to check that {@link AStarRouter}
 * returns optimal routes. Only the cost is needed for that comparison.
 */
public class DijkstraRouter {
    private final RoadGraph graph;

    public DijkstraRouter(RoadGraph graph) {
        this.graph = graph;
    }

    /** Shortest path cost from start to goal, or {@code Double.POSITIVE_INFINITY} if unreachable. */
    public double shortestCost(long startId, long goalId) {
        if (graph.getNode(startId) == null || graph.getNode(goalId) == null) return Double.POSITIVE_INFINITY;
        if (startId == goalId) return 0.0;
        Map<Long, Double> dist = new HashMap<>();
        PriorityQueue<Entry> pq = new PriorityQueue<>((a, b) -> Double.compare(a.dist(), b.dist()));
        dist.put(startId, 0.0);
        pq.add(new Entry(startId, 0.0));
        while (!pq.isEmpty()) {
            Entry entry = pq.poll();
            long id = entry.id();
            double d = entry.dist();
            if (id == goalId) return d;
            if (d > dist.getOrDefault(id, Double.POSITIVE_INFINITY)) continue;
            for (RoadGraph.Edge edge : graph.getEdges(id)) {
                double nd = d + edge.weight();
                if (nd < dist.getOrDefault(edge.toId(), Double.POSITIVE_INFINITY)) {
                    dist.put(edge.toId(), nd);
                    pq.add(new Entry(edge.toId(), nd));
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private record Entry(long id, double dist) {}
}
