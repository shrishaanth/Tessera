package com.geotracker.routing;

import com.geotracker.model.Position;
import com.geotracker.model.RouteResult;
import com.geotracker.routing.RoadGraph;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RouteVisualizationJUnitTest {

    @Test
    void routeNodesAreRoadGraphCoordinates() {
        RoadGraph graph = RoadGraph.builder()
                .addGrid(3, 3, 10.0)
                .build();
        AStarRouter router = new AStarRouter(graph);
        RouteResult result = router.findRoute(0, 0L, 8L);
        assertFalse(result.nodeIds().isEmpty());

        for (long nodeId : result.nodeIds()) {
            RoadGraph.Node node = graph.getNode(nodeId);
            assertNotNull(node);
        }
    }
}
