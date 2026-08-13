package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;
import com.geotracker.routing.RoadGraph;

import java.util.*;

public class VehicleSimulator {
    private final RoadGraph graph;
    private final List<SimulatedVehicle> vehicles;
    private final double speed;
    private final double dt;

    public VehicleSimulator(RoadGraph graph, int vehicleCount, double speed, double dt, long seed) {
        this.graph = graph;
        this.speed = speed;
        this.dt = dt;
        this.vehicles = new ArrayList<>();
        initialize(vehicleCount, seed);
    }

    private void initialize(int count, long seed) {
        List<RoadGraph.Node> nodes = new ArrayList<>(graph.getAllNodes());
        if (nodes.isEmpty()) return;
        Random random = new Random(seed);
        for (int i = 0; i < count; i++) {
            long startId = nodes.get(random.nextInt(nodes.size())).id();
            List<RoadGraph.Edge> edges = graph.getEdges(startId);
            long nextId = edges.isEmpty() ? startId : edges.get(random.nextInt(edges.size())).toId();
            vehicles.add(new SimulatedVehicle(i, startId, nextId, seed));
        }
    }

    public List<PositionUpdate> tick() {
        List<PositionUpdate> updates = new ArrayList<>();
        for (SimulatedVehicle vehicle : vehicles) {
            PositionUpdate update = vehicle.update(graph, speed, dt);
            if (update != null) {
                updates.add(update);
            }
        }
        return updates;
    }

    public int getVehicleCount() {
        return vehicles.size();
    }
}
