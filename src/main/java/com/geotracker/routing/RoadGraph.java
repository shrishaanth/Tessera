package com.geotracker.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class RoadGraph {
    public record Node(long id, double x, double y) {}
    public record Edge(long fromId, long toId, double weight) {}

    private final Map<Long, Node> nodes;
    private final Map<Long, List<Edge>> adjacency;

    public RoadGraph() {
        this.nodes = new HashMap<>();
        this.adjacency = new HashMap<>();
    }

    public void addNode(Node node) {
        nodes.put(node.id(), node);
        adjacency.putIfAbsent(node.id(), new ArrayList<>());
    }

    public void addEdge(Edge edge) {
        adjacency.computeIfAbsent(edge.fromId(), k -> new ArrayList<>()).add(edge);
    }

    public Node getNode(long id) {
        return nodes.get(id);
    }

    public List<Edge> getEdges(long fromId) {
        return adjacency.getOrDefault(fromId, List.of());
    }

    public Collection<Node> getAllNodes() {
        return nodes.values();
    }

    public Node findNearestNode(double x, double y) {
        Node best = null;
        double bestDist = Double.MAX_VALUE;
        for (Node node : nodes.values()) {
            double dx = node.x() - x;
            double dy = node.y() - y;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < bestDist) {
                bestDist = d;
                best = node;
            }
        }
        return bestDist <= 100 ? best : null;
    }

    public static RoadGridBuilder builder() {
        return new RoadGridBuilder();
    }

    public static RoadGraph loadFromJson(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> root = mapper.readValue(json, Map.class);
        List<Map<?, ?>> nodes = (List<Map<?, ?>>) root.get("nodes");
        List<Map<?, ?>> edges = (List<Map<?, ?>>) root.get("edges");

        RoadGraph graph = new RoadGraph();
        for (Map<?, ?> n : nodes) {
            long id = ((Number) n.get("id")).longValue();
            double x = ((Number) n.get("x")).doubleValue();
            double y = ((Number) n.get("y")).doubleValue();
            graph.addNode(new Node(id, x, y));
        }
        for (Map<?, ?> e : edges) {
            long from = ((Number) e.get("from")).longValue();
            long to = ((Number) e.get("to")).longValue();
            double weight = ((Number) e.get("weight")).doubleValue();
            graph.addEdge(new Edge(from, to, weight));
        }
        return graph;
    }

    public static class RoadGridBuilder {
        private final List<Node> nodes = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();
        private long nextId = 0;

        public RoadGridBuilder addGrid(int cols, int rows, double spacing) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    nodes.add(new Node(nextId++, c * spacing, r * spacing));
                }
            }
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    long id = r * cols + c;
                    if (c < cols - 1) {
                        edges.add(new Edge(id, id + 1, spacing));
                        edges.add(new Edge(id + 1, id, spacing));
                    }
                    if (r < rows - 1) {
                        edges.add(new Edge(id, id + cols, spacing));
                        edges.add(new Edge(id + cols, id, spacing));
                    }
                }
            }
            return this;
        }

        public RoadGraph build() {
            RoadGraph graph = new RoadGraph();
            for (Node node : nodes) {
                graph.addNode(node);
            }
            for (Edge edge : edges) {
                graph.addEdge(edge);
            }
            return graph;
        }
    }
}
