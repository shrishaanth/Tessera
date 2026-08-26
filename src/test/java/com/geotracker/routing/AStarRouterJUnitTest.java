package com.geotracker.routing;

import com.geotracker.model.Position;
import com.geotracker.model.RouteResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class AStarRouterJUnitTest {

    @Test
    void directPath() {
        RoadGraph graph = RoadGraph.builder()
                .addGrid(2, 2, 10.0)
                .build();
        AStarRouter router = new AStarRouter(graph);
        RouteResult result = router.findRoute(0, 0L, 1L);
        assertEquals(List.of(0L, 1L), result.nodeIds());
        assertEquals(10.0, result.totalCost(), 0.001);
    }

    @Test
    void triangleGraph() {
        RoadGraph graph = new RoadGraph();
        graph.addNode(new RoadGraph.Node(0, 0, 0));
        graph.addNode(new RoadGraph.Node(1, 10, 0));
        graph.addNode(new RoadGraph.Node(2, 5, 5));
        graph.addEdge(new RoadGraph.Edge(0, 1, 10));
        graph.addEdge(new RoadGraph.Edge(0, 2, 10));
        graph.addEdge(new RoadGraph.Edge(2, 1, 10));

        AStarRouter router = new AStarRouter(graph);
        RouteResult result = router.findRoute(0, 0L, 1L);
        assertEquals(List.of(0L, 1L), result.nodeIds());
    }

    @Test
    void noPath() {
        RoadGraph graph = new RoadGraph();
        graph.addNode(new RoadGraph.Node(0, 0, 0));
        graph.addNode(new RoadGraph.Node(1, 10, 0));

        AStarRouter router = new AStarRouter(graph);
        RouteResult result = router.findRoute(0, 0L, 1L);
        assertTrue(result.nodeIds().isEmpty());
    }

    @Test
    void sameStartEnd() {
        RoadGraph graph = RoadGraph.builder()
                .addGrid(2, 2, 10.0)
                .build();
        AStarRouter router = new AStarRouter(graph);
        RouteResult result = router.findRoute(0, 0L, 0L);
        assertEquals(List.of(0L), result.nodeIds());
    }
}
