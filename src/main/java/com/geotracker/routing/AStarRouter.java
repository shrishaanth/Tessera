package com.geotracker.routing;

import com.geotracker.model.Position;
import com.geotracker.model.RouteResult;

import java.util.*;

public class AStarRouter {
    private final RoadGraph graph;
    private final boolean useGeoDistance;

    public AStarRouter(RoadGraph graph) {
        this.graph = graph;
        this.useGeoDistance = detectGeoCoordinates(graph);
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
        openSet.add(new NodeEntry(startId, 0.0, heuristic(start, goal)));

        while (!openSet.isEmpty()) {
            NodeEntry current = openSet.poll();
            long currentId = current.id();
            double currentG = current.gScore();

            // Skip stale entries
            if (currentG > gScore.getOrDefault(currentId, Double.POSITIVE_INFINITY)) continue;

            if (currentId == goalId) {
                List<Long> path = reconstructPath(cameFrom, currentId);
                return new RouteResult(path, currentG, vehicleId);
            }

            for (RoadGraph.Edge edge : graph.getEdges(currentId)) {
                long neighborId = edge.toId();
                double tentativeG = currentG + edge.weight();
                if (tentativeG < gScore.getOrDefault(neighborId, Double.POSITIVE_INFINITY)) {
                    cameFrom.put(neighborId, currentId);
                    gScore.put(neighborId, tentativeG);
                    RoadGraph.Node neighbor = graph.getNode(neighborId);
                    double h = heuristic(neighbor, goal);
                    openSet.add(new NodeEntry(neighborId, tentativeG, tentativeG + h));
                }
            }
        }

        return new RouteResult(List.of(), 0, vehicleId);
    }

    private static boolean detectGeoCoordinates(RoadGraph graph) {
        for (RoadGraph.Node node : graph.getAllNodes()) {
            if (node.x() < -180 || node.x() > 180 || node.y() < -90 || node.y() > 90) {
                return false;
            }
        }
        return !graph.getAllNodes().isEmpty();
    }

    private double distance(RoadGraph.Node a, RoadGraph.Node b) {
        if (useGeoDistance) {
            return GeoUtils.haversineMeters(a.y(), a.x(), b.y(), b.x());
        }
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private double heuristic(RoadGraph.Node a, RoadGraph.Node b) {
        return distance(a, b);
    }

    private RoadGraph.Node findNearestNode(double x, double y) {
        RoadGraph.Node best = null;
        double bestDist = Double.MAX_VALUE;
        for (RoadGraph.Node node : graph.getAllNodes()) {
            double d;
            if (useGeoDistance) {
                d = GeoUtils.haversineMeters(y, x, node.y(), node.x());
            } else {
                double dx = node.x() - x;
                double dy = node.y() - y;
                d = Math.sqrt(dx * dx + dy * dy);
            }
            if (d < bestDist) {
                bestDist = d;
                best = node;
            }
        }
        double maxSnap = useGeoDistance ? 500.0 : 100.0;
        if (bestDist > maxSnap) return null;
        return best;
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

    private record NodeEntry(long id, double gScore, double fScore) {}
}
