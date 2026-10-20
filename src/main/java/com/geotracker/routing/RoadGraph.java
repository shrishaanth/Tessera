package com.geotracker.routing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class RoadGraph {
    public record Node(long id, double x, double y) {}
    /** Directed edge. {@code speedMps} is the posted speed limit in m/s, or 0 when unknown. */
    public record Edge(long fromId, long toId, double weight, double speedMps) {
        public Edge(long fromId, long toId, double weight) {
            this(fromId, toId, weight, 0.0);
        }
    }

    private final Map<Long, Node> nodes;
    private final Map<Long, List<Edge>> adjacency;
    private Boolean geoGraph; // cached isGeoGraph(); reset when nodes change

    public RoadGraph() {
        this.nodes = new HashMap<>();
        this.adjacency = new HashMap<>();
    }

    public void addNode(Node node) {
        geoGraph = null;
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
        boolean isGeo = isGeoGraph();
        for (Node node : nodes.values()) {
            double d;
            if (isGeo) {
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
        double maxSnap = isGeo ? 500.0 : 100.0;
        return bestDist <= maxSnap ? best : null;
    }

    public boolean isGeoGraph() {
        Boolean cached = geoGraph;
        if (cached != null) return cached;
        boolean geo = !nodes.isEmpty();
        for (Node node : nodes.values()) {
            if (node.x() < -180 || node.x() > 180 || node.y() < -90 || node.y() > 90) {
                geo = false;
                break;
            }
        }
        geoGraph = geo;
        return geo;
    }

    /**
     * Streaming loader for large graphs ({@code {"nodes":[{"id","x","y"}...], "edges":[{"from","to","weight",
     * "speed"?}...]}}; other top-level fields are ignored). Unlike {@link #loadFromJson(String)} it does not
     * build an intermediate object tree, so a 100,000-edge graph loads quickly with little garbage.
     */
    public static RoadGraph loadFromJson(InputStream in) throws IOException {
        RoadGraph graph = new RoadGraph();
        try (JsonParser p = new JsonFactory().createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("Road graph: expected a JSON object");
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String section = p.currentName();
                JsonToken t = p.nextToken();
                if ("nodes".equals(section) && t == JsonToken.START_ARRAY) {
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        long id = 0;
                        double x = Double.NaN, y = Double.NaN;
                        while (p.nextToken() == JsonToken.FIELD_NAME) {
                            String f = p.currentName();
                            p.nextToken();
                            switch (f) {
                                case "id" -> id = p.getLongValue();
                                case "x" -> x = p.getDoubleValue();
                                case "y" -> y = p.getDoubleValue();
                                default -> p.skipChildren();
                            }
                        }
                        if (Double.isNaN(x) || Double.isNaN(y)) throw new IOException("Road graph: node " + id + " lacks x/y");
                        graph.addNode(new Node(id, x, y));
                    }
                } else if ("edges".equals(section) && t == JsonToken.START_ARRAY) {
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        long from = 0, to = 0;
                        double weight = Double.NaN, speed = 0;
                        while (p.nextToken() == JsonToken.FIELD_NAME) {
                            String f = p.currentName();
                            p.nextToken();
                            switch (f) {
                                case "from" -> from = p.getLongValue();
                                case "to" -> to = p.getLongValue();
                                case "weight" -> weight = p.getDoubleValue();
                                case "speed" -> speed = p.getDoubleValue();
                                default -> p.skipChildren();
                            }
                        }
                        if (Double.isNaN(weight)) throw new IOException("Road graph: edge " + from + "->" + to + " lacks a weight");
                        graph.addEdge(new Edge(from, to, weight, speed));
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
        return graph;
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
