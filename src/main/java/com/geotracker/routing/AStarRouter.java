package com.geotracker.routing;

import com.geotracker.model.Position;
import com.geotracker.model.RouteResult;

import java.util.*;

public class AStarRouter {
    private final RoadGraph graph;

    public AStarRouter(RoadGraph graph) {
        this.graph = graph;
    }

    public RouteResult findRoute(long vehicleId, Position startPos, double destX, double destY) {
        RoadGraph.Node startNode = findNearestNode(startPos.x(), startPos.y());
        RoadGraph.Node destNode = findNearestNode(destX, destY);
        if (startNode == null || destNode == null) {
            return new RouteResult(List.of(), 0, vehicleId);
        }

        return findRoute(vehicleId, startNode.id(), destNode.id());
    }

    public RouteResult findRoute(long vehicleId, long startId, long goalId) {
        if (startId == goalId) {
            return new RouteResult(List.of(startId), 0, vehicleId);
        }

        RoadGraph.Node start = graph.getNode(startId);
        RoadGraph.Node goal = graph.getNode(goalId);
        if (start == null || goal == null) {
            return new RouteResult(List.of(), 0, vehicleId);
        }

        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        PriorityQueue<NodeEntry> openSet = new PriorityQueue<>(Comparator.comparingDouble(e -> e.fScore));

        gScore.put(startId, 0.0);
        openSet.add(new NodeEntry(startId, heuristic(start, goal)));

        while (!openSet.isEmpty()) {
            NodeEntry current = openSet.poll();
            long currentId = current.id();

            if (currentId == goalId) {
                List<Long> path = reconstructPath(cameFrom, currentId);
                double cost = gScore.get(goalId);
                return new RouteResult(path, cost, vehicleId);
            }

            for (RoadGraph.Edge edge : graph.getEdges(currentId)) {
                long neighborId = edge.toId();
                double tentativeG = gScore.get(currentId) + edge.weight();
                if (tentativeG < gScore.getOrDefault(neighborId, Double.POSITIVE_INFINITY)) {
                    cameFrom.put(neighborId, currentId);
                    gScore.put(neighborId, tentativeG);
                    RoadGraph.Node neighbor = graph.getNode(neighborId);
                    double h = heuristic(neighbor, goal);
                    openSet.add(new NodeEntry(neighborId, tentativeG + h));
                }
            }
        }

        return new RouteResult(List.of(), 0, vehicleId);
    }

    private double heuristic(RoadGraph.Node a, RoadGraph.Node b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private List<Long> reconstructPath(Map<Long, Long> cameFrom, long currentId) {
        List<Long> path = new ArrayList<>();
        path.add(currentId);
        while (cameFrom.containsKey(currentId)) {
            currentId = cameFrom.get(currentId);
            path.add(0, currentId);
        }
        return path;
    }

    private RoadGraph.Node findNearestNode(double x, double y) {
        RoadGraph.Node best = null;
        double bestDist = Double.MAX_VALUE;
        for (RoadGraph.Node node : graph.getAllNodes()) {
            double dx = node.x() - x;
            double dy = node.y() - y;
            double dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                best = node;
            }
        }
        return best;
    }

    private record NodeEntry(long id, double fScore) {}
}
