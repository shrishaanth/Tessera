package com.geotracker.service;

import com.geotracker.model.Position;
import com.geotracker.model.ZoneEvent;
import com.geotracker.routing.GeoUtils;
import com.geotracker.routing.RoadGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end checks of the city-scale simulation through the application service. */
class CitySimulationServiceTest {

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
    void serviceUsesTheCityRoadNetwork() {
        ApplicationService.RoadNetworkStats stats = service.getRoadNetworkStats();
        assertTrue(stats.nodes() > 50_000, "Nodes: " + stats.nodes());
        assertEquals(stats.nodes(), stats.routableNodes());
        assertTrue(stats.roadKm() > 1_000, "Road km: " + stats.roadKm());
        // The map's initial view (dataset bounds) covers the whole network, several km across.
        var b = service.getDatasetBounds();
        assertTrue(GeoUtils.haversineMeters(b.minY(), b.minX(), b.maxY(), b.maxX()) > 12_000);
    }

    @Test
    @Timeout(30)
    void thousandVehiclesOccupyManyNeighbourhoods() throws InterruptedException {
        service.setVehicleCount(1_000);
        service.setSimulationSpeed(10.0);
        service.startSimulation();
        Thread.sleep(3_000);
        List<ApplicationService.VehicleInfo> vehicles = service.getAllVehicles();
        assertEquals(1_000, vehicles.size());
        Map<String, Integer> cells = new HashMap<>();
        int nearGasWorks = 0;
        for (var v : vehicles) {
            cells.merge((int) ((v.x() + 122.405) * 75) + ":" + (int) ((v.y() - 47.595) * 111.195), 1, Integer::sum);
            if (GeoUtils.haversineMeters(47.6456, -122.3344, v.y(), v.x()) < 500) nearGasWorks++;
        }
        assertTrue(cells.size() >= 60, "Only " + cells.size() + " 1-km cells occupied");
        assertTrue(nearGasWorks < 30, nearGasWorks + " of 1,000 vehicles within 500 m of Gas Works Park");
    }

    @Test
    void roadPolylinesCoverEveryEdgeOnce() {
        List<double[]> lines = service.getRoadPolylines();
        long points = 0;
        for (double[] l : lines) {
            assertTrue(l.length >= 4 && l.length % 2 == 0);
            points += l.length / 2 - 1;
        }
        // Each undirected road segment appears exactly once across all polylines.
        Set<String> undirected = new HashSet<>();
        RoadGraph g = service.getActiveGraph();
        for (RoadGraph.Node n : g.getAllNodes()) {
            for (RoadGraph.Edge e : g.getEdges(n.id())) undirected.add(Math.min(n.id(), e.toId()) + ":" + Math.max(n.id(), e.toId()));
        }
        assertEquals(undirected.size(), points);
        assertTrue(lines.size() < undirected.size() / 2, "Chains should be merged into polylines");
    }

    @Test
    void routePlanReportsTravelTimeAtPostedLimits() {
        var plan = service.planRoute(-122.3331, 47.6097, -122.3132, 47.6615); // Downtown -> University District
        assertTrue(plan.found(), plan.message());
        assertTrue(plan.matchesReference());
        assertTrue(plan.distanceMeters() > 5_000, "Distance " + plan.distanceMeters());
        double avgKmh = plan.distanceMeters() / plan.travelSeconds() * 3.6;
        assertTrue(avgKmh > 25 && avgKmh < 100, "Average speed along route " + avgKmh + " km/h");
    }

    @Test
    void demoRoutingNeedsARunningSimulationAndARoadInsideTheZone() {
        String id = service.getGeofenceEngine().createZone("Lake", square(47.6400, -122.3340, 0.0005), Set.of(), true, true);
        assertTrue(service.demoRouteVehiclesInto(id, 5).vehicleIds().isEmpty(), "Stopped: nothing to route");
        service.startSimulation();
        var result = service.demoRouteVehiclesInto(id, 5); // middle of Lake Union: no road inside
        assertTrue(result.vehicleIds().isEmpty());
        assertEquals(0, result.roadNodesInZone());
        service.stopSimulation();
    }

    @Test
    @Timeout(90)
    void demoRoutedVehiclesProduceRealEnterAndExitEvents() throws InterruptedException {
        service.setVehicleCount(1_000);
        service.setSimulationSpeed(10.0);
        service.startSimulation();
        Thread.sleep(1_500);
        // A ~120 m zone around a road node in South Lake Union.
        RoadGraph.Node node = service.getActiveGraph().findNearestNode(-122.3383, 47.6236);
        assertNotNull(node);
        String zone = service.getGeofenceEngine().createZone("Demo target", square(node.y(), node.x(), 0.0008), Set.of(), true, true);
        // Paused, vehicles cannot cross any boundary, so any event appearing now would come from the demo.
        service.pauseSimulation();
        Thread.sleep(700);
        long eventsBefore = service.getEnterEventCount() + service.getExitEventCount();
        var result = service.demoRouteVehiclesInto(zone, 5);
        assertEquals(5, result.vehicleIds().size(), result.message());
        Set<Long> routed = new HashSet<>(result.vehicleIds());
        Thread.sleep(700);
        assertEquals(eventsBefore, service.getEnterEventCount() + service.getExitEventCount(),
                "The demo itself must not record events or change statistics");
        service.resumeSimulation();

        boolean entered = false, exited = false;
        long deadline = System.currentTimeMillis() + 75_000;
        while (System.currentTimeMillis() < deadline && !(entered && exited)) {
            Thread.sleep(250);
            for (ZoneEvent ev : service.getEventHistory()) {
                if (!ev.zoneId().equals(zone) || !routed.contains(ev.vehicleId())) continue;
                if (ev.type() == ZoneEvent.EventType.ENTER) entered = true;
                if (ev.type() == ZoneEvent.EventType.EXIT && entered) exited = true;
            }
        }
        assertTrue(entered, "No routed vehicle was detected entering the zone");
        assertTrue(exited, "No routed vehicle was detected leaving the zone after its next trip began");
    }

    private static List<Position> square(double lat, double lng, double half) {
        return List.of(new Position(lng - half, lat - half, 0), new Position(lng + half, lat - half, 0),
                new Position(lng + half, lat + half, 0), new Position(lng - half, lat + half, 0));
    }
}
