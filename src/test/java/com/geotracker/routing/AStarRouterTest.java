package com.geotracker.routing;

import com.geotracker.model.Position;
import com.geotracker.model.RouteResult;

import java.util.List;

public class AStarRouterTest {
    public static void main(String[] args) {
        boolean allPassed = true;
        allPassed &= testDirectPath();
        allPassed &= testTriangleGraph();
        allPassed &= testNoPath();
        allPassed &= testSameStartEnd();
        if (allPassed) {
            System.out.println("All AStarRouter tests passed");
        } else {
            System.out.println("Some AStarRouter tests FAILED");
            System.exit(1);
        }
    }

    private static boolean testDirectPath() {
        try {
            RoadGraph graph = RoadGraph.builder()
                    .addGrid(2, 2, 10.0)
                    .build();
            AStarRouter router = new AStarRouter(graph);
            RouteResult result = router.findRoute(0, 0L, 1L);
            assert List.of(0L, 1L).equals(result.nodeIds()) : "Expected [0, 1], got " + result.nodeIds();
            assert Math.abs(result.totalCost() - 10.0) < 0.001 : "Expected cost 10.0";
            System.out.println("PASS: directPath");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: directPath - " + t.getMessage());
            return false;
        }
    }

    private static boolean testTriangleGraph() {
        try {
            RoadGraph graph = new RoadGraph();
            graph.addNode(new RoadGraph.Node(0, 0, 0));
            graph.addNode(new RoadGraph.Node(1, 10, 0));
            graph.addNode(new RoadGraph.Node(2, 5, 5));
            graph.addEdge(new RoadGraph.Edge(0, 1, 10));
            graph.addEdge(new RoadGraph.Edge(0, 2, 10));
            graph.addEdge(new RoadGraph.Edge(2, 1, 10));

            AStarRouter router = new AStarRouter(graph);
            RouteResult result = router.findRoute(0, 0L, 1L);
            assert List.of(0L, 1L).equals(result.nodeIds()) : "Expected [0, 1], got " + result.nodeIds();
            System.out.println("PASS: triangleGraph");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: triangleGraph - " + t.getMessage());
            return false;
        }
    }

    private static boolean testNoPath() {
        try {
            RoadGraph graph = new RoadGraph();
            graph.addNode(new RoadGraph.Node(0, 0, 0));
            graph.addNode(new RoadGraph.Node(1, 10, 0));

            AStarRouter router = new AStarRouter(graph);
            RouteResult result = router.findRoute(0, 0L, 1L);
            assert result.nodeIds().isEmpty() : "Expected empty path";
            System.out.println("PASS: noPath");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: noPath - " + t.getMessage());
            return false;
        }
    }

    private static boolean testSameStartEnd() {
        try {
            RoadGraph graph = RoadGraph.builder()
                    .addGrid(2, 2, 10.0)
                    .build();
            AStarRouter router = new AStarRouter(graph);
            RouteResult result = router.findRoute(0, 0L, 0L);
            assert List.of(0L).equals(result.nodeIds()) : "Expected [0], got " + result.nodeIds();
            System.out.println("PASS: sameStartEnd");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: sameStartEnd - " + t.getMessage());
            return false;
        }
    }
}
