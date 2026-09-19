package com.geotracker.routing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RoadGraphJsonLoaderJUnitTest {

    @Test
    void loadFromJsonBuildsCorrectGraph() throws Exception {
        String json = """
        {
          "nodes": [
            {"id": 1, "x": 0.0, "y": 0.0},
            {"id": 2, "x": 10.0, "y": 0.0},
            {"id": 3, "x": 5.0, "y": 5.0}
          ],
          "edges": [
            {"from": 1, "to": 2, "weight": 10.0},
            {"from": 1, "to": 3, "weight": 7.07},
            {"from": 2, "to": 1, "weight": 10.0}
          ]
        }
        """;

        RoadGraph graph = RoadGraph.loadFromJson(json);

        assertEquals(3, graph.getAllNodes().size());
        assertNotNull(graph.getNode(1));
        assertNotNull(graph.getNode(2));
        assertNotNull(graph.getNode(3));
        assertEquals(0.0, graph.getNode(1).x());
        assertEquals(10.0, graph.getNode(2).x());

        var edges1 = graph.getEdges(1);
        assertEquals(2, edges1.size());

        var edges2 = graph.getEdges(2);
        assertEquals(1, edges2.size());
        assertEquals(1, edges2.get(0).toId());

        var edges3 = graph.getEdges(3);
        assertTrue(edges3.isEmpty());
    }

    @Test
    void loadFromJsonRejectsMalformedInput() {
        assertThrows(Exception.class, () -> RoadGraph.loadFromJson("not json"));
        assertThrows(Exception.class, () -> RoadGraph.loadFromJson("{\"nodes\": []}"));
    }
}
