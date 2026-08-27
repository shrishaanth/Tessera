package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;
import com.geotracker.routing.RoadGraph;

import java.util.*;

public class SimulatedVehicle {
    private final long vehicleId;
    private long currentNodeId;
    private long nextNodeId;
    private double progress;
    private final Random random;
    private final List<Long> largestComponentNodes;

    public SimulatedVehicle(long vehicleId, long startNodeId, long nextNodeId, long seed, List<Long> largestComponentNodes) {
        this.vehicleId = vehicleId;
        this.currentNodeId = startNodeId;
        this.nextNodeId = nextNodeId;
        this.progress = 0.0;
        this.random = new Random(seed + vehicleId);
        this.largestComponentNodes = largestComponentNodes;
    }

    public PositionUpdate update(RoadGraph graph, double speed, double dt) {
        RoadGraph.Node current = graph.getNode(currentNodeId);
        RoadGraph.Node next = graph.getNode(nextNodeId);
        if (current == null || next == null) return null;

        double dx = next.x() - current.x();
        double dy = next.y() - current.y();
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist == 0) return null;

        double step = speed * dt;
        progress += step / dist;

        double x, y;
        if (progress >= 1.0) {
            progress = 0.0;
            currentNodeId = nextNodeId;
            List<RoadGraph.Edge> edges = graph.getEdges(currentNodeId);
            if (edges.isEmpty()) {
                long[] pair = VehicleSpawnHelper.pickStartAndNext(graph, largestComponentNodes, random);
                currentNodeId = pair[0];
                nextNodeId = pair[1];
                RoadGraph.Node newNode = graph.getNode(currentNodeId);
                x = newNode.x();
                y = newNode.y();
            } else {
                RoadGraph.Edge edge = edges.get(random.nextInt(edges.size()));
                nextNodeId = edge.toId();
                RoadGraph.Node newNext = graph.getNode(nextNodeId);
                x = newNext.x();
                y = newNext.y();
            }
        } else {
            x = current.x() + dx * progress;
            y = current.y() + dy * progress;
        }

        return new PositionUpdate(vehicleId, x, y, System.currentTimeMillis());
    }
}
