package com.geotracker.routing;

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

    public static RoadGridBuilder builder() {
        return new RoadGridBuilder();
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
