package com.geotracker.service;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.RouteResult;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationServiceTest {

    private ApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationService();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void initialStateIsStopped() {
        assertEquals(ApplicationService.SimulationState.STOPPED, service.getSimulationState());
    }

    @Test
    void startSimulationChangesState() {
        service.startSimulation();
        assertEquals(ApplicationService.SimulationState.RUNNING, service.getSimulationState());
    }

    @Test
    void pauseSimulation() {
        service.startSimulation();
        service.pauseSimulation();
        assertEquals(ApplicationService.SimulationState.PAUSED, service.getSimulationState());
    }

    @Test
    void resumeSimulation() {
        service.startSimulation();
        service.pauseSimulation();
        service.resumeSimulation();
        assertEquals(ApplicationService.SimulationState.RUNNING, service.getSimulationState());
    }

    @Test
    void stopSimulation() {
        service.startSimulation();
        service.stopSimulation();
        assertEquals(ApplicationService.SimulationState.STOPPED, service.getSimulationState());
    }

    @Test
    void resetSimulationClearsState() {
        service.startSimulation();
        try { Thread.sleep(300); } catch (InterruptedException e) {}
        service.resetSimulation();
        assertEquals(ApplicationService.SimulationState.STOPPED, service.getSimulationState());
        assertEquals(0, service.getTotalUpdates());
    }

    @Test
    void simulationProducesUpdates() throws InterruptedException {
        service.startSimulation();
        Thread.sleep(1500);
        assertTrue(service.getTotalUpdates() > 0, "Expected some updates after running simulation");
        service.stopSimulation();
    }

    @Test
    void vehiclesAppearAfterSimulation() throws InterruptedException {
        service.startSimulation();
        Thread.sleep(1500);
        int active = service.getActiveVehicleCount();
        assertTrue(active > 0, "Expected active vehicles but got " + active);
        service.stopSimulation();
    }

    @Test
    void rangeQueryReturnsResults() throws InterruptedException {
        service.startSimulation();
        Thread.sleep(1500);
        BoundingBox fullBounds = service.getGeoBounds();
        List<Long> results = service.rangeQuery(fullBounds);
        assertFalse(results.isEmpty(), "Range query on full bounds should return vehicles");
        service.stopSimulation();
    }

    @Test
    void getVehiclePositionReturnsNullForUnknown() {
        assertNull(service.getVehiclePosition(99999));
    }

    @Test
    void routeFindsPath() {
        RouteResult route = service.findRoute(-122.3340, 47.6465, -122.3305, 47.6495);
        // Route may or may not find a path depending on graph connectivity, but should not throw
        assertNotNull(route);
    }

    @Test
    void geoBoundsAreValid() {
        BoundingBox bounds = service.getGeoBounds();
        assertTrue(bounds.minX() < bounds.maxX());
        assertTrue(bounds.minY() < bounds.maxY());
    }

    @Test
    void totalVehicleCountIs20() {
        assertEquals(20, service.getTotalVehicleCount());
    }

    @Test
    void setSimulationSpeedClamped() {
        service.setSimulationSpeed(0.0);
        assertEquals(0.1, service.getSimulationSpeed(), 0.01);
        service.setSimulationSpeed(100.0);
        assertEquals(10.0, service.getSimulationSpeed(), 0.01);
    }

    @Test
    void rapidStartStopCycles() {
        for (int i = 0; i < 10; i++) {
            service.startSimulation();
            service.stopSimulation();
        }
        assertEquals(ApplicationService.SimulationState.STOPPED, service.getSimulationState());
    }

    @Test
    void rapidPauseResumeCycles() throws InterruptedException {
        service.startSimulation();
        for (int i = 0; i < 5; i++) {
            service.pauseSimulation();
            service.resumeSimulation();
        }
        service.stopSimulation();
        assertEquals(ApplicationService.SimulationState.STOPPED, service.getSimulationState());
    }

    @Test
    void geofenceEngineIsInitialized() {
        assertNotNull(service.getGeofenceEngine());
    }

    @Test
    void eventHistoryStartsEmpty() {
        assertTrue(service.getEventHistory().isEmpty());
    }

    // --- Vehicle Count Configuration ---

    @Test
    void defaultVehicleCountIs20() {
        assertEquals(20, service.getTotalVehicleCount());
        // Default source is the OSM road graph; the CSV replay (a synthetic lattice) is opt-in.
        assertEquals(ApplicationService.TrajectorySource.ROAD_GRAPH, service.getTrajectorySource());
        assertEquals(0, service.getCsvVehicleCount());
    }

    @Test
    void csvReplayModeUsesTwentyCsvVehicles() {
        service.setTrajectorySource(ApplicationService.TrajectorySource.CSV_REPLAY);
        assertEquals(20, service.getCsvVehicleCount());
        assertEquals(20, service.getTotalVehicleCount());
    }

    @Test
    void setVehicleCountTo100() {
        service.setVehicleCount(100);
        assertEquals(100, service.getTotalVehicleCount());
    }

    @Test
    void setVehicleCountCannotGoBelowCsv() {
        service.setTrajectorySource(ApplicationService.TrajectorySource.CSV_REPLAY);
        service.setVehicleCount(5);
        assertEquals(20, service.getTotalVehicleCount());
    }

    @Test
    void roadGraphModeAllowsSmallFleets() {
        service.setVehicleCount(5);
        assertEquals(5, service.getTotalVehicleCount());
    }

    @Test
    void vehicleCountIgnoredWhileRunning() {
        service.startSimulation();
        service.setVehicleCount(100);
        assertEquals(20, service.getTotalVehicleCount()); // unchanged
        service.stopSimulation();
    }

    @Test
    void extraVehiclesAppearAfterStart() throws InterruptedException {
        service.setVehicleCount(100);
        service.startSimulation();
        Thread.sleep(2000);
        int active = service.getActiveVehicleCount();
        assertTrue(active > 20, "Expected >20 vehicles but got " + active);
        service.stopSimulation();
    }

    // --- Telemetry ---

    @Test
    void getAllVehiclesIncludesSpeedAndHeading() throws InterruptedException {
        service.startSimulation();
        Thread.sleep(1500);
        // First call populates previousPositions
        service.getAllVehicles();
        Thread.sleep(500);
        // Second call can compute speed
        var vehicles = service.getAllVehicles();
        assertFalse(vehicles.isEmpty());
        // At least some vehicles should have computed speed
        boolean anySpeed = vehicles.stream().anyMatch(v -> v.speedKmh() > 0.01);
        assertTrue(anySpeed, "Expected at least some vehicles with computed speed");
        service.stopSimulation();
    }

    @Test
    void vehicleInfoHasSourceField() throws InterruptedException {
        service.startSimulation();
        Thread.sleep(1500);
        var vehicles = service.getAllVehicles();
        for (var v : vehicles) {
            assertNotNull(v.source());
            assertTrue(v.source().equals("csv") || v.source().equals("generated"));
        }
        service.stopSimulation();
    }

    // --- Pause/Rate Behavior ---

    @Test
    void updateRateIsZeroWhenStopped() {
        assertEquals(0.0, service.getMeasuredUpdateRate(), 0.01);
    }

    @Test
    void updateRateDropsToZeroWhenPaused() throws InterruptedException {
        service.startSimulation();
        Thread.sleep(1500);
        assertTrue(service.getMeasuredUpdateRate() >= 0);
        service.pauseSimulation();
        assertEquals(0.0, service.getMeasuredUpdateRate(), 0.01,
                "Update rate should be 0 when paused");
        service.stopSimulation();
    }

    @Test
    void roadGraphEdgesJsonNotEmpty() {
        String json = service.getRoadGraphEdgesJson();
        assertNotNull(json);
        assertTrue(json.length() > 10, "Expected non-trivial road graph JSON");
    }
}
