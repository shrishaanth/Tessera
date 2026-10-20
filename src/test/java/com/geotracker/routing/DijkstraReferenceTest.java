package com.geotracker.routing;

import com.geotracker.model.RouteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DijkstraReferenceTest {

    private RoadGraph graph;
    private AStarRouter astarRouter;

    @BeforeEach
    void setUp() {
        graph = RoadGraph.builder().addGrid(10, 10, 50.0).build();
        astarRouter = new AStarRouter(graph);
    }

    /**
     * Reference Dijkstra implementation for verification.
     */
    private RouteResult dijkstra(long startId, long goalId) {
        if (startId == goalId) return new RouteResult(List.of(startId), 0, -1);
        if (graph.getNode(startId) == null || graph.getNode(goalId) == null) {
            return new RouteResult(List.of(), 0, -1);
        }

        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));

        dist.put(startId, 0.0);
        pq.offer(new long[]{startId, Double.doubleToLongBits(0.0)});

        while (!pq.isEmpty()) {
            long[] entry = pq.poll();
            long currentId = entry[0];
            double currentDist = Double.longBitsToDouble(entry[1]);

            if (currentId == goalId) break;
            if (currentDist > dist.getOrDefault(currentId, Double.MAX_VALUE)) continue;

            for (RoadGraph.Edge edge : graph.getEdges(currentId)) {
                double newDist = currentDist + edge.weight();
                if (newDist < dist.getOrDefault(edge.toId(), Double.MAX_VALUE)) {
                    dist.put(edge.toId(), newDist);
                    prev.put(edge.toId(), currentId);
                    pq.offer(new long[]{edge.toId(), Double.doubleToLongBits(newDist)});
                }
            }
        }

        if (!dist.containsKey(goalId)) return new RouteResult(List.of(), 0, -1);

        List<Long> path = new ArrayList<>();
        long current = goalId;
        while (current != startId) {
            path.add(0, current);
            current = prev.get(current);
        }
        path.add(0, startId);
        return new RouteResult(path, dist.get(goalId), -1);
    }

    @Test
    void astarMatchesDijkstraOnGrid() {
        Random rng = new Random(42);
        List<Long> nodeIds = new ArrayList<>();
        for (RoadGraph.Node n : graph.getAllNodes()) nodeIds.add(n.id());

        int matches = 0;
        int total = 100;
        for (int i = 0; i < total; i++) {
            long from = nodeIds.get(rng.nextInt(nodeIds.size()));
            long to = nodeIds.get(rng.nextInt(nodeIds.size()));

            RouteResult astarResult = astarRouter.findRoute(-1, from, to);
            RouteResult dijkstraResult = dijkstra(from, to);

            assertEquals(dijkstraResult.totalCost(), astarResult.totalCost(), 1e-9,
                    String.format("Cost mismatch for route %d -> %d: dijkstra=%.4f, astar=%.4f",
                            from, to, dijkstraResult.totalCost(), astarResult.totalCost()));

            if (from != to && !astarResult.nodeIds().isEmpty()) {
                assertEquals(dijkstraResult.nodeIds().get(0), astarResult.nodeIds().get(0),
                        "Path should start at the same node");
                assertEquals(dijkstraResult.nodeIds().get(dijkstraResult.nodeIds().size() - 1),
                        astarResult.nodeIds().get(astarResult.nodeIds().size() - 1),
                        "Path should end at the same node");
            }
            matches++;
        }
        assertEquals(total, matches, "All routes should match");
    }

    @Test
    void astarMatchesDijkstraOnOsmGraph() throws Exception {
        try (var is = getClass().getResourceAsStream("/roadgraph/osm-roadgraph.json")) {
            if (is == null) return; // skip if missing
            String json = new String(is.readAllBytes());
            RoadGraph osmGraph = RoadGraph.loadFromJson(json);
            AStarRouter osmRouter = new AStarRouter(osmGraph);

            List<Long> nodeIds = new ArrayList<>();
            for (RoadGraph.Node n : osmGraph.getAllNodes()) nodeIds.add(n.id());

            Random rng = new Random(123);
            for (int i = 0; i < 50; i++) {
                long from = nodeIds.get(rng.nextInt(nodeIds.size()));
                long to = nodeIds.get(rng.nextInt(nodeIds.size()));

                RouteResult astarResult = osmRouter.findRoute(-1, from, to);
                // Use the internal dijkstra on osmGraph
                RoadGraph finalOsmGraph = osmGraph;
                Map<Long, Double> dist = new HashMap<>();
                Map<Long, Long> prev = new HashMap<>();
                PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));
                dist.put(from, 0.0);
                pq.offer(new long[]{from, Double.doubleToLongBits(0.0)});
                while (!pq.isEmpty()) {
                    long[] entry = pq.poll();
                    long curId = entry[0];
                    double curDist = Double.longBitsToDouble(entry[1]);
                    if (curId == to) break;
                    if (curDist > dist.getOrDefault(curId, Double.MAX_VALUE)) continue;
                    for (RoadGraph.Edge e : finalOsmGraph.getEdges(curId)) {
                        double nd = curDist + e.weight();
                        if (nd < dist.getOrDefault(e.toId(), Double.MAX_VALUE)) {
                            dist.put(e.toId(), nd);
                            prev.put(e.toId(), curId);
                            pq.offer(new long[]{e.toId(), Double.doubleToLongBits(nd)});
                        }
                    }
                }

                if (dist.containsKey(to)) {
                    double dijkstraCost = dist.get(to);
                    assertEquals(dijkstraCost, astarResult.totalCost(), 1e-6,
                            "OSM route cost mismatch: " + from + " -> " + to);
                }
            }
        }
    }

    @Test
    void selfRouteReturnsStartNode() {
        RouteResult result = astarRouter.findRoute(-1, 0, 0);
        assertEquals(1, result.nodeIds().size());
        assertEquals(0, result.nodeIds().get(0));
        assertEquals(0, result.totalCost(), 1e-9);
    }

    @Test
    void unreachableReturnsEmpty() {
        // Node 99999 doesn't exist
        RouteResult result = astarRouter.findRoute(-1, 0, 99999);
        assertTrue(result.nodeIds().isEmpty());
    }
}
