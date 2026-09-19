package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;
import com.geotracker.routing.RoadGraph;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class SimulatedVehicleTest {

    @Test
    void vehicleAtDeadEndKeepsMoving() {
        RoadGraph graph = new RoadGraph();
        graph.addNode(new RoadGraph.Node(0, 0, 0));
        graph.addNode(new RoadGraph.Node(1, 100, 0));
        graph.addNode(new RoadGraph.Node(2, 200, 0));
        graph.addEdge(new RoadGraph.Edge(0, 1, 100));
        graph.addEdge(new RoadGraph.Edge(1, 2, 100));
        graph.addEdge(new RoadGraph.Edge(2, 1, 100));

        List<Long> component = List.of(0L, 1L, 2L);
        SimulatedVehicle vehicle = new SimulatedVehicle(1, 0, 1, 42L, component);
        for (int i = 0; i < 20; i++) {
            PositionUpdate update = vehicle.update(graph, 200.0, 0.1);
            assertNotNull(update, "Vehicle should keep producing updates at tick " + i);
        }
    }

    @Test
    void deadEndRespawnsInLargestComponentNotDisconnected() {
        RoadGraph graph = new RoadGraph();
        graph.addNode(new RoadGraph.Node(1, 0.0, 0.0));
        graph.addNode(new RoadGraph.Node(2, 1.0, 0.0));
        graph.addNode(new RoadGraph.Node(3, 10.0, 0.0));
        graph.addNode(new RoadGraph.Node(4, 2.0, 0.0));
        graph.addEdge(new RoadGraph.Edge(1, 2, 1.0));
        graph.addEdge(new RoadGraph.Edge(4, 1, 1.0));

        List<Long> component = List.of(1L, 2L, 4L);
        SimulatedVehicle vehicle = new SimulatedVehicle(99, 1, 2, 42, component);

        for (int i = 0; i < 50; i++) {
            PositionUpdate update = vehicle.update(graph, 10.0, 1.0);
            if (update != null) {
                assertTrue(update.x() < 5.0,
                    "Vehicle should not teleport to disconnected node C (x=10). Got x=" + update.x());
            }
        }
    }
}
