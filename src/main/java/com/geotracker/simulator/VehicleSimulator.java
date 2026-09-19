package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;
import com.geotracker.routing.RoadGraph;

import java.util.*;

public class VehicleSimulator {
    private final RoadGraph graph;
    private final List<SimulatedVehicle> vehicles;
    private final double speed;
    private final double dt;
    private final List<Long> largestComponentNodes;

    public VehicleSimulator(RoadGraph graph, int vehicleCount, double speed, double dt, long seed) {
        this.graph = graph;
        this.speed = speed;
        this.dt = dt;
        this.vehicles = new ArrayList<>();
        this.largestComponentNodes = computeLargestComponent(graph);
        initialize(vehicleCount, seed);
    }

    private static List<Long> computeLargestComponent(RoadGraph graph) {
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (RoadGraph.Node node : graph.getAllNodes()) {
            List<Long> neighbors = new ArrayList<>();
            for (RoadGraph.Edge edge : graph.getEdges(node.id())) {
                neighbors.add(edge.toId());
            }
            adjacency.put(node.id(), neighbors);
        }

        Set<Long> visited = new HashSet<>();
        List<Long> largest = new ArrayList<>();

        for (Long start : adjacency.keySet()) {
            if (visited.contains(start)) continue;
            List<Long> component = new ArrayList<>();
            Deque<Long> stack = new ArrayDeque<>();
            stack.push(start);
            visited.add(start);
            while (!stack.isEmpty()) {
                Long id = stack.pop();
                component.add(id);
                for (Long neighbor : adjacency.getOrDefault(id, List.of())) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        stack.push(neighbor);
                    }
                }
            }
            if (component.size() > largest.size()) {
                largest = component;
            }
        }
        return largest;
    }

    private void initialize(int count, long seed) {
        if (largestComponentNodes.isEmpty()) return;
        Random random = new Random(seed);
        for (int i = 0; i < count; i++) {
            long[] pair = VehicleSpawnHelper.pickStartAndNext(graph, largestComponentNodes, random);
            long startId = pair[0];
            long nextId = pair[1];
            vehicles.add(new SimulatedVehicle(i, startId, nextId, seed, largestComponentNodes));
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
