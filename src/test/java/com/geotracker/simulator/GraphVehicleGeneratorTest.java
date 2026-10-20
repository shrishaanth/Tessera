package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;
import com.geotracker.routing.RoadGraph;
import com.geotracker.util.Config;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GraphVehicleGeneratorTest {

    private static RoadGraph osmGraph;
    private static RoadGraph gridGraph;

    @BeforeAll
    static void loadGraphs() throws Exception {
        try (InputStream is = GraphVehicleGeneratorTest.class.getResourceAsStream(Config.ROADGRAPH_RESOURCE)) {
            if (is != null) {
                osmGraph = RoadGraph.loadFromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        gridGraph = RoadGraph.builder().addGrid(10, 10, 50.0).build();
    }

    @Test
    void generatesCorrectCount() {
        RoadGraph graph = osmGraph != null ? osmGraph : gridGraph;
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 80, 20, 42L);
        List<PositionUpdate> updates = gen.tick(0.2);
        assertEquals(80, updates.size());
    }

    @Test
    void vehicleIdsStartFromBaseId() {
        RoadGraph graph = osmGraph != null ? osmGraph : gridGraph;
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 10, 20, 42L);
        List<PositionUpdate> updates = gen.tick(0.2);
        for (int i = 0; i < 10; i++) {
            assertEquals(20 + i, updates.get(i).vehicleId());
        }
    }

    @Test
    void vehicleIdsAreUnique() {
        RoadGraph graph = osmGraph != null ? osmGraph : gridGraph;
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 100, 20, 42L);
        List<PositionUpdate> updates = gen.tick(0.2);
        Set<Long> ids = new HashSet<>();
        for (PositionUpdate u : updates) {
            assertTrue(ids.add(u.vehicleId()), "Duplicate vehicle ID: " + u.vehicleId());
        }
    }

    @Test
    void positionsAreOnGraphArea() {
        if (osmGraph == null) return;
        GraphVehicleGenerator gen = new GraphVehicleGenerator(osmGraph, 50, 20, 42L);
        List<PositionUpdate> updates = gen.tick(0.2);
        for (PositionUpdate u : updates) {
            // Should be in Seattle area
            assertTrue(u.x() >= -122.4 && u.x() <= -122.3,
                    "Longitude out of range: " + u.x());
            assertTrue(u.y() >= 47.6 && u.y() <= 47.7,
                    "Latitude out of range: " + u.y());
        }
    }

    @Test
    void deterministicReplay() {
        RoadGraph graph = osmGraph != null ? osmGraph : gridGraph;
        GraphVehicleGenerator gen1 = new GraphVehicleGenerator(graph, 20, 20, 42L);
        GraphVehicleGenerator gen2 = new GraphVehicleGenerator(graph, 20, 20, 42L);

        for (int tick = 0; tick < 50; tick++) {
            List<PositionUpdate> u1 = gen1.tick(0.2);
            List<PositionUpdate> u2 = gen2.tick(0.2);
            assertEquals(u1.size(), u2.size());
            for (int i = 0; i < u1.size(); i++) {
                assertEquals(u1.get(i).vehicleId(), u2.get(i).vehicleId());
                assertEquals(u1.get(i).x(), u2.get(i).x(), 1e-9,
                        "Mismatch at tick " + tick + " vehicle " + i);
                assertEquals(u1.get(i).y(), u2.get(i).y(), 1e-9);
            }
        }
    }

    @Test
    void resetRestoresInitialPositions() {
        RoadGraph graph = osmGraph != null ? osmGraph : gridGraph;
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 10, 20, 42L);
        List<PositionUpdate> first = gen.tick(0.2);
        gen.tick(0.2);
        gen.tick(0.2);
        gen.reset();
        List<PositionUpdate> afterReset = gen.tick(0.2);
        assertEquals(first.size(), afterReset.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).x(), afterReset.get(i).x(), 1e-9);
            assertEquals(first.get(i).y(), afterReset.get(i).y(), 1e-9);
        }
    }

    @Test
    void trajectoryIsContinuous() {
        RoadGraph graph = osmGraph != null ? osmGraph : gridGraph;
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 5, 20, 42L);

        List<PositionUpdate> prev = gen.tick(0.2);
        for (int tick = 0; tick < 100; tick++) {
            List<PositionUpdate> curr = gen.tick(0.2);
            for (int i = 0; i < curr.size(); i++) {
                double dx = curr.get(i).x() - prev.get(i).x();
                double dy = curr.get(i).y() - prev.get(i).y();
                double dist = Math.sqrt(dx * dx + dy * dy);
                // At 15 m/s max speed, 0.2s dt, max movement ~3m which is ~0.00003 degrees
                // Allow generous bound of 0.01 degrees (~1km) for edge traversals
                assertTrue(dist < 0.01,
                        "Vehicle " + i + " jumped " + dist + " degrees in one tick");
            }
            prev = curr;
        }
    }

    @Test
    void largeCountWorks() {
        RoadGraph graph = osmGraph != null ? osmGraph : gridGraph;
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 1000, 20, 42L);
        List<PositionUpdate> updates = gen.tick(0.2);
        assertEquals(1000, updates.size());
        // Verify all unique IDs
        Set<Long> ids = new HashSet<>();
        for (PositionUpdate u : updates) ids.add(u.vehicleId());
        assertEquals(1000, ids.size());
    }

    @Test
    void speedIsPhysicallyPlausible() {
        if (osmGraph == null) return;
        GraphVehicleGenerator gen = new GraphVehicleGenerator(osmGraph, 20, 20, 42L);
        List<PositionUpdate> prev = gen.tick(0.2);
        List<PositionUpdate> curr = gen.tick(0.2);

        for (int i = 0; i < curr.size(); i++) {
            double dLat = curr.get(i).y() - prev.get(i).y();
            double dLng = curr.get(i).x() - prev.get(i).x();
            // Very rough: 1 degree lat ~= 111km
            double distMeters = Math.sqrt(
                    (dLat * 111320) * (dLat * 111320) +
                    (dLng * 111320 * Math.cos(Math.toRadians(47.648))) * (dLng * 111320 * Math.cos(Math.toRadians(47.648)))
            );
            double speedKmh = (distMeters / 0.2) * 3.6;
            // Should be under 200 km/h (allows for short-edge traversal bursts)
            assertTrue(speedKmh < 200,
                    "Vehicle " + i + " speed " + speedKmh + " km/h exceeds plausible limit");
        }
    }
}
