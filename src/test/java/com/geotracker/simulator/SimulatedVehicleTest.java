package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;
import com.geotracker.routing.RoadGraph;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SimulatedVehicleTest {

    @Test
    void vehicleAtDeadEndKeepsMoving() {
        RoadGraph graph = new RoadGraph();
        graph.addNode(new RoadGraph.Node(0, 0, 0));
        graph.addNode(new RoadGraph.Node(1, 100, 0));

        SimulatedVehicle vehicle = new SimulatedVehicle(1, 0, 1, 42L);
        PositionUpdate update1 = vehicle.update(graph, 200.0, 0.1);
        assertNotNull(update1);

        graph = new RoadGraph();
        graph.addNode(new RoadGraph.Node(0, 0, 0));
        graph.addNode(new RoadGraph.Node(1, 100, 0));
        graph.addNode(new RoadGraph.Node(2, 200, 0));
        graph.addEdge(new RoadGraph.Edge(0, 1, 100));
        graph.addEdge(new RoadGraph.Edge(1, 2, 100));
        graph.addEdge(new RoadGraph.Edge(2, 1, 100));

        vehicle = new SimulatedVehicle(1, 0, 1, 42L);
        for (int i = 0; i < 20; i++) {
            PositionUpdate update = vehicle.update(graph, 200.0, 0.1);
            assertNotNull(update, "Vehicle should keep producing updates at tick " + i);
        }
    }
}
