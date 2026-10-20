package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;
import com.geotracker.routing.GeoUtils;
import com.geotracker.routing.RoadGraph;
import com.geotracker.util.Config;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Per-tick movement limits for road-graph vehicles. */
class GraphVehicleMotionTest {

    private static final double MAX_SPEED_MPS = 15.0;
    private static RoadGraph osmGraph;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream is = GraphVehicleMotionTest.class.getResourceAsStream(Config.ROADGRAPH_RESOURCE)) {
            assertNotNull(is, "OSM road graph resource missing");
            osmGraph = RoadGraph.loadFromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void noVehicleMovesFurtherThanItsSpeedAllowsInOneTick() {
        // Regression: when a vehicle crossed a node, the remaining progress fraction of the old edge was
        // applied as a fraction of the (often much longer) next edge, teleporting the vehicle.
        double dt = 0.2;
        GraphVehicleGenerator gen = new GraphVehicleGenerator(osmGraph, 2_000, 0, 42L);
        List<PositionUpdate> prev = gen.tick(dt);
        double worst = 0;
        for (int t = 0; t < 300; t++) {
            List<PositionUpdate> cur = gen.tick(dt);
            for (int i = 0; i < cur.size(); i++) {
                PositionUpdate a = prev.get(i), b = cur.get(i);
                double d = GeoUtils.haversineMeters(a.y(), a.x(), b.y(), b.x());
                worst = Math.max(worst, d);
            }
            prev = cur;
        }
        // Straight-line displacement can never exceed the path length driven (speed * dt).
        assertTrue(worst <= MAX_SPEED_MPS * dt + 1e-6, "Vehicle moved " + worst + " m in one 0.2 s tick");
    }

    @Test
    void largeTimeStepsStillRespectSpeed() {
        // 10x playback advances 2 s per tick, crossing several short edges at once.
        double dt = 2.0;
        GraphVehicleGenerator gen = new GraphVehicleGenerator(osmGraph, 500, 0, 7L);
        List<PositionUpdate> prev = gen.tick(dt);
        for (int t = 0; t < 100; t++) {
            List<PositionUpdate> cur = gen.tick(dt);
            for (int i = 0; i < cur.size(); i++) {
                PositionUpdate a = prev.get(i), b = cur.get(i);
                double d = GeoUtils.haversineMeters(a.y(), a.x(), b.y(), b.x());
                assertTrue(d <= MAX_SPEED_MPS * dt + 1e-6, "Vehicle " + b.vehicleId() + " moved " + d + " m");
            }
            prev = cur;
        }
    }
}
