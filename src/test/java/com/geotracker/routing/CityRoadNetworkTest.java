package com.geotracker.routing;

import com.geotracker.simulator.GraphVehicleGenerator;
import com.geotracker.util.Config;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The central-Seattle road graph used by the simulation: coverage, connectivity and edge data. */
class CityRoadNetworkTest {

    private static RoadGraph graph;
    private static String rawJson;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream raw = CityRoadNetworkTest.class.getResourceAsStream(Config.CITY_ROADGRAPH_RESOURCE)) {
            assertNotNull(raw, "City road graph resource missing");
            rawJson = new String(new GZIPInputStream(raw).readAllBytes(), StandardCharsets.UTF_8);
        }
        graph = RoadGraph.loadFromJson(new java.io.ByteArrayInputStream(rawJson.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void coversMultipleNeighbourhoodsNotJustGasWorksPark() {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (RoadGraph.Node n : graph.getAllNodes()) {
            minX = Math.min(minX, n.x()); maxX = Math.max(maxX, n.x());
            minY = Math.min(minY, n.y()); maxY = Math.max(maxY, n.y());
        }
        double widthM = GeoUtils.haversineMeters(47.64, minX, 47.64, maxX);
        double heightM = GeoUtils.haversineMeters(minY, -122.33, maxY, -122.33);
        assertTrue(widthM > 8_500 && heightM > 10_000, "Extent " + widthM + " x " + heightM + " m");
        assertTrue(graph.getAllNodes().size() > 50_000, "Nodes: " + graph.getAllNodes().size());
        // Roads near well-known places in different neighbourhoods.
        double[][] places = {
                {47.6097, -122.3331},  // Downtown
                {47.6687, -122.3847},  // Ballard
                {47.6615, -122.3132},  // University District
                {47.6253, -122.3222},  // Capitol Hill
                {47.6370, -122.3570},  // Queen Anne
                {47.6510, -122.3500},  // Fremont
        };
        for (double[] p : places) {
            RoadGraph.Node n = graph.findNearestNode(p[1], p[0]);
            assertNotNull(n, "No road near " + Arrays.toString(p));
            assertTrue(GeoUtils.haversineMeters(p[0], p[1], n.y(), n.x()) < 150, "Road too far from " + Arrays.toString(p));
        }
    }

    @Test
    void everyNodeIsInOneStronglyConnectedComponent() {
        GraphVehicleGenerator.Network net = GraphVehicleGenerator.network(graph);
        assertEquals(net.nodeCount(), net.routableNodeCount(),
                "Every node must reach every other along legal directions (no one-way traps, no islands)");
    }

    @Test
    void edgesHaveHaversineLengthsAndPositiveSpeedLimits() {
        int edges = 0;
        for (RoadGraph.Node a : graph.getAllNodes()) {
            for (RoadGraph.Edge e : graph.getEdges(a.id())) {
                RoadGraph.Node b = graph.getNode(e.toId());
                assertNotNull(b, "Edge to a missing node");
                assertEquals(GeoUtils.haversineMeters(a.y(), a.x(), b.y(), b.x()), e.weight(), 0.006);
                assertTrue(e.speedMps() > 1 && e.speedMps() < 35, "Speed " + e.speedMps() + " m/s");
                edges++;
            }
        }
        assertTrue(edges > 100_000);
    }

    @Test
    void oneWayStreetsArePreserved() {
        long oneWay = 0, total = 0;
        for (RoadGraph.Node a : graph.getAllNodes()) {
            for (RoadGraph.Edge e : graph.getEdges(a.id())) {
                total++;
                boolean reverse = graph.getEdges(e.toId()).stream().anyMatch(r -> r.toId() == a.id());
                if (!reverse) oneWay++;
            }
        }
        // Downtown's one-way grid and the freeways make up a sizeable share of directed edges.
        assertTrue(oneWay > total * 0.1, oneWay + " of " + total + " edges are one-way");
    }

    @Test
    void sourceAndLicenceAreRecorded() {
        assertTrue(rawJson.contains("© OpenStreetMap contributors"));
        assertTrue(rawJson.contains("ODbL"));
        assertTrue(rawJson.contains("largest strongly connected component"));
        // Only motor-vehicle road classes were requested; footpaths and trails are not in the filter.
        assertFalse(rawJson.contains("footway") || rawJson.contains("cycleway") || rawJson.contains("\"path\""));
    }
}
