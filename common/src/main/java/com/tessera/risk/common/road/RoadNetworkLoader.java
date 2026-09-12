package com.tessera.risk.common.road;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads the OpenStreetMap-derived road network JSON (produced by
 * {@code infra/scripts/build_roadgraph.py}) into a CSR {@link RoadNetwork}.
 *
 * <p>Deliberately free of any framework dependency: the same loader is used by the
 * Kafka producer, the Spark jobs and the reporting service, and Spark executors
 * must be able to construct it without a Spring context on the classpath.
 */
public final class RoadNetworkLoader {

    private static final double DEFAULT_SPEED_KPH = 30.0;

    private RoadNetworkLoader() { }

    /** Load from the packaged classpath resource. */
    public static RoadNetwork fromClasspath(String resource) {
        try (InputStream in = RoadNetworkLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Road network resource not found: " + resource);
            }
            return parse(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read road network " + resource, e);
        }
    }

    /** Load from a file on disk — used by the offline generator and Spark jobs. */
    public static RoadNetwork fromFile(Path path) {
        try {
            return parse(new ObjectMapper().readTree(Files.readAllBytes(path)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read road network " + path, e);
        }
    }

    private record RawEdge(int from, int to, double lengthM, double speedKph,
                           double travelSec, String name) { }

    /** Build a {@link RoadNetwork} from an in-memory JSON tree. */
    public static RoadNetwork parse(JsonNode root) {
        JsonNode nodes = root.path("nodes");
        JsonNode edgesJson = root.path("edges");
        if (!nodes.isArray() || !edgesJson.isArray() || nodes.isEmpty()) {
            throw new IllegalStateException("Road network JSON has no nodes or edges");
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
            double speedKph = edge.path("speedKph").asDouble(DEFAULT_SPEED_KPH);
            if (speedKph <= 0) {
                speedKph = DEFAULT_SPEED_KPH;
            }
            double lengthM = edge.path("lengthM").asDouble(0);
            if (lengthM <= 0) {
                lengthM = GeoDistance.between(lat[from], lon[from], lat[to], lon[to]);
            }
            double travel = edge.path("travelSec").asDouble(Double.NaN);
            if (Double.isNaN(travel) || travel <= 0) {
                travel = lengthM / (speedKph / 3.6);
            }
            String name = edge.hasNonNull("name") ? edge.path("name").asText() : null;
            edges.add(new RawEdge(from, to, lengthM, speedKph, travel, name));
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
        double[] fwdLengthM = new double[m];
        double[] fwdSpeedLimit = new double[m];
        double[] fwdTravelSec = new double[m];
        String[] fwdName = new String[m];
        int[] revSource = new int[m];
        int[] fwdCursor = fwdRowStart.clone();
        int[] revCursor = revRowStart.clone();

        for (RawEdge e : edges) {
            int fs = fwdCursor[e.from]++;
            fwdTarget[fs] = e.to;
            fwdLengthM[fs] = e.lengthM;
            fwdSpeedLimit[fs] = e.speedKph;
            fwdTravelSec[fs] = e.travelSec;
            fwdName[fs] = e.name;
            revSource[revCursor[e.to]++] = e.from;
        }

        String areaName = root.path("name").asText("Unnamed area");
        return new RoadNetwork(areaName, lat, lon, osmId,
                fwdRowStart, fwdTarget, fwdLengthM, fwdSpeedLimit, fwdTravelSec, fwdName,
                revRowStart, revSource);
    }

    /** Local copy of the haversine, so the loader has no cyclic dependency on geo. */
    private static final class GeoDistance {
        static double between(double lat1, double lon1, double lat2, double lon2) {
            return com.tessera.risk.common.geo.GeoMath.haversineMeters(lat1, lon1, lat2, lon2);
        }
    }
}
