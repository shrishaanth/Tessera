package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;
import com.geotracker.util.Config;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrajectorySimulatorTest {

    @Test
    void tickProducesUpdates() {
        TrajectorySimulator sim = new TrajectorySimulator(
                Config.TRAJECTORY_RESOURCE, 20, 1.0, 1.0 / 5.0);
        List<PositionUpdate> updates = sim.tick();
        assertEquals(20, updates.size());
    }

    @Test
    void updatesHaveValidCoordinates() {
        TrajectorySimulator sim = new TrajectorySimulator(
                Config.TRAJECTORY_RESOURCE, 20, 1.0, 1.0 / 5.0);
        List<PositionUpdate> updates = sim.tick();
        for (PositionUpdate u : updates) {
            assertTrue(u.y() >= 47.0 && u.y() <= 48.0,
                    "Latitude out of range: " + u.y());
            assertTrue(u.x() >= -123.0 && u.x() <= -122.0,
                    "Longitude out of range: " + u.x());
        }
    }

    @Test
    void resetRestoresInitialState() {
        TrajectorySimulator sim = new TrajectorySimulator(
                Config.TRAJECTORY_RESOURCE, 20, 1.0, 1.0 / 5.0);
        sim.tick();
        sim.tick();
        sim.reset();
        List<PositionUpdate> first = sim.tick();
        sim.reset();
        List<PositionUpdate> second = sim.tick();
        // After reset, same tick should produce same positions
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).x(), second.get(i).x(), 1e-9);
            assertEquals(first.get(i).y(), second.get(i).y(), 1e-9);
        }
    }

    @Test
    void deterministicReplay() {
        TrajectorySimulator sim1 = new TrajectorySimulator(
                Config.TRAJECTORY_RESOURCE, 20, 1.0, 1.0 / 5.0);
        TrajectorySimulator sim2 = new TrajectorySimulator(
                Config.TRAJECTORY_RESOURCE, 20, 1.0, 1.0 / 5.0);

        for (int tick = 0; tick < 50; tick++) {
            List<PositionUpdate> u1 = sim1.tick();
            List<PositionUpdate> u2 = sim2.tick();
            assertEquals(u1.size(), u2.size());
            for (int i = 0; i < u1.size(); i++) {
                assertEquals(u1.get(i).vehicleId(), u2.get(i).vehicleId());
                assertEquals(u1.get(i).x(), u2.get(i).x(), 1e-9);
                assertEquals(u1.get(i).y(), u2.get(i).y(), 1e-9);
            }
        }
    }

    @Test
    void vehicleIdsAreSequential() {
        TrajectorySimulator sim = new TrajectorySimulator(
                Config.TRAJECTORY_RESOURCE, 10, 1.0, 1.0 / 5.0);
        List<PositionUpdate> updates = sim.tick();
        for (int i = 0; i < 10; i++) {
            assertEquals(i, updates.get(i).vehicleId());
        }
    }
}
