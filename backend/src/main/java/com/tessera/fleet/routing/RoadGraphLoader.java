package com.tessera.fleet.routing;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.tessera.fleet.config.FleetProperties;

/**
 * Loads the OSM-derived routing graph JSON (produced by
 * {@code infra/scripts/build_roadgraph.py}) into a CSR {@link RoadGraph} with
 * both forward and reverse edge indices.
 */
@Component
public class RoadGraphLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String resourceLocation;

    public RoadGraphLoader(ResourceLoader resourceLoader, ObjectMapper objectMapper,
                           FleetProperties properties) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.resourceLocation = properties.roadGraphResource();
    }

    public RoadGraph load() {
        Resource resource = resourceLoader.getResource(resourceLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("Road graph resource not found: " + resourceLocation);
        }
        try (InputStream in = resource.getInputStream()) {
            return parse(objectMapper.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read road graph " + resourceLocation, e);
        }
    }

    private record RawEdge(int from, int to, double travelSec) { }

    /** Build a {@link RoadGraph} from an in-memory JSON tree (also used by tests). */
    public static RoadGraph parse(JsonNode root) {
        JsonNode nodes = root.path("nodes");
        JsonNode edgesJson = root.path("edges");
        if (!nodes.isArray() || !edgesJson.isArray() || nodes.isEmpty()) {
            throw new IllegalStateException("Road graph JSON has no nodes/edges");
        }

        int n = nodes.size();
        double[] lat = new double[n];
        double[] lon = new double[n];
        long[] osmId = new long[n];
        Map<Long, Integer> osmToIndex = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            JsonNode node = nodes.get(i);
            long id = node.path("id").asLong();
            osmId[i] = id;
            lat[i] = node.path("lat").asDouble();
            lon[i] = node.path("lon").asDouble();
            osmToIndex.put(id, i);
        }

        List<RawEdge> edges = new ArrayList<>(edgesJson.size());
        int[] outDegree = new int[n];
        int[] inDegree = new int[n];
        for (JsonNode edge : edgesJson) {
            Integer from = osmToIndex.get(edge.path("from").asLong());
            Integer to = osmToIndex.get(edge.path("to").asLong());
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            double travel = edge.path("travelSec").asDouble(Double.NaN);
            if (Double.isNaN(travel) || travel <= 0) {
                double lengthM = edge.path("lengthM").asDouble(0);
                double speedKph = edge.path("speedKph").asDouble(30);
                travel = lengthM / (Math.max(1.0, speedKph) / 3.6);
            }
            edges.add(new RawEdge(from, to, travel));
            outDegree[from]++;
            inDegree[to]++;
        }

        int m = edges.size();
        int[] fwdRowStart = new int[n + 1];
        int[] revRowStart = new int[n + 1];
        for (int i = 0; i < n; i++) {
            fwdRowStart[i + 1] = fwdRowStart[i] + outDegree[i];
            revRowStart[i + 1] = revRowStart[i] + inDegree[i];
        }

        int[] fwdTarget = new int[m];
        double[] fwdTravelSec = new double[m];
        int[] revSource = new int[m];
        double[] revTravelSec = new double[m];
        int[] fwdCursor = fwdRowStart.clone();
        int[] revCursor = revRowStart.clone();
        for (RawEdge e : edges) {
            int fs = fwdCursor[e.from]++;
            fwdTarget[fs] = e.to;
            fwdTravelSec[fs] = e.travelSec;
            int rs = revCursor[e.to]++;
            revSource[rs] = e.from;
            revTravelSec[rs] = e.travelSec;
        }

        String areaName = root.path("name").asText("Unnamed area");
        return new RoadGraph(areaName, lat, lon, osmId,
                fwdRowStart, fwdTarget, fwdTravelSec,
                revRowStart, revSource, revTravelSec);
    }
}
