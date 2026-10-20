package com.geotracker.simulator;

import com.geotracker.geofence.GeofenceEngine;
import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.PositionUpdate;
import com.geotracker.model.ZoneEvent;
import com.geotracker.routing.GeoUtils;
import com.geotracker.routing.RoadGraph;
import com.geotracker.util.Config;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Trip-based movement on the central-Seattle road graph. */
class CityTripSimulationTest {

    private static final double GAS_WORKS_LAT = 47.6456, GAS_WORKS_LNG = -122.3344;
    private static RoadGraph graph;
    private static double maxLimitMps;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream raw = CityTripSimulationTest.class.getResourceAsStream(Config.CITY_ROADGRAPH_RESOURCE)) {
            assertNotNull(raw, "City road graph resource missing");
            graph = RoadGraph.loadFromJson(new GZIPInputStream(raw));
        }
        for (RoadGraph.Node n : graph.getAllNodes()) {
            for (RoadGraph.Edge e : graph.getEdges(n.id())) maxLimitMps = Math.max(maxLimitMps, e.speedMps());
        }
    }

    private static boolean hasDirectedEdge(long from, long to) {
        for (RoadGraph.Edge e : graph.getEdges(from)) if (e.toId() == to) return true;
        return false;
    }

    /** Distance in metres from a point to the segment between two nodes. */
    private static double distanceToSegment(double lng, double lat, RoadGraph.Node a, RoadGraph.Node b) {
        double k = 111_195 * Math.cos(Math.toRadians(lat));
        double ax = (a.x() - lng) * k, ay = (a.y() - lat) * 111_195, bx = (b.x() - lng) * k, by = (b.y() - lat) * 111_195;
        double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }

    @Test
    void spawnPositionsAreSpreadAcrossTheCity() {
        // Regression: vehicles used to be spawned uniformly over graph nodes of a 0.27 km2 graph, so they all
        // started within a few hundred metres of Gas Works Park.
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 1_000, 0, 42L);
        List<PositionUpdate> start = gen.tick(0.0);
        Map<String, Integer> cells = new HashMap<>();
        int nearGasWorks = 0;
        for (PositionUpdate u : start) {
            String cell = (int) ((u.x() + 122.405) * 75_000 / 1_000) + ":" + (int) ((u.y() - 47.595) * 111_195 / 1_000);
            cells.merge(cell, 1, Integer::sum);
            if (GeoUtils.haversineMeters(GAS_WORKS_LAT, GAS_WORKS_LNG, u.y(), u.x()) < 500) nearGasWorks++;
        }
        int busiest = Collections.max(cells.values());
        assertTrue(cells.size() >= 60, "1,000 vehicles only occupy " + cells.size() + " 1-km cells");
        assertTrue(busiest <= 50, "Busiest 1-km cell holds " + busiest + " of 1,000 vehicles");
        assertTrue(nearGasWorks <= 30, nearGasWorks + " vehicles start within 500 m of Gas Works Park");
    }

    @Test
    void everyVehicleGetsAReachableDestinationKilometresAway() {
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 500, 0, 7L);
        List<PositionUpdate> start = gen.tick(0.0);
        double[] tripLengths = new double[500];
        for (int i = 0; i < 500; i++) {
            assertTrue(gen.hasRoute(i), "Vehicle " + i + " has no route");
            long[] path = gen.plannedPathNodes(i);
            assertEquals(gen.destinationNode(i), path[path.length - 1], "Route must end at the destination");
            RoadGraph.Node dest = graph.getNode(gen.destinationNode(i));
            tripLengths[i] = GeoUtils.haversineMeters(start.get(i).y(), start.get(i).x(), dest.y(), dest.x());
            for (int k = 0; k + 1 < path.length; k++) {
                assertTrue(hasDirectedEdge(path[k], path[k + 1]), "Route uses a non-existent or wrong-way edge");
            }
        }
        Arrays.sort(tripLengths);
        assertTrue(tripLengths[250] > 1_500, "Median planned trip " + tripLengths[250] + " m");
    }

    @Test
    @Timeout(60)
    void vehiclesStayOnLegalRoadsAndNeverJump() {
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 300, 0, 11L);
        double dt = 0.5;
        List<PositionUpdate> prev = gen.tick(dt);
        for (int tick = 0; tick < 1_200; tick++) {   // 10 simulated minutes
            List<PositionUpdate> cur = gen.tick(dt);
            for (int i = 0; i < cur.size(); i++) {
                PositionUpdate a = prev.get(i), b = cur.get(i);
                double moved = GeoUtils.haversineMeters(a.y(), a.x(), b.y(), b.x());
                assertTrue(moved <= maxLimitMps * dt + 1e-6, "Vehicle " + i + " jumped " + moved + " m in " + dt + " s");
                long[] edge = gen.currentEdge(i);
                assertTrue(hasDirectedEdge(edge[0], edge[1]), "Vehicle " + i + " drives an illegal direction");
                double off = distanceToSegment(b.x(), b.y(), graph.getNode(edge[0]), graph.getNode(edge[1]));
                assertTrue(off < 0.5, "Vehicle " + i + " is " + off + " m off its road");
            }
            prev = cur;
        }
    }

    @Test
    @Timeout(60)
    void arrivingVehiclesContinueOnANewTripFromTheirDestination() {
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 200, 0, 3L);
        gen.tick(0.0);
        long[] destination = new long[200];
        for (int i = 0; i < 200; i++) destination[i] = gen.destinationNode(i);
        int newTrips = 0;
        for (int tick = 0; tick < 1_800; tick++) {   // 30 simulated minutes
            List<PositionUpdate> cur = gen.tick(1.0);
            for (int i = 0; i < 200; i++) {
                long d = gen.destinationNode(i);
                if (d != destination[i]) {
                    // The vehicle picked a new destination only after reaching the old one.
                    RoadGraph.Node reached = graph.getNode(destination[i]);
                    double gap = GeoUtils.haversineMeters(reached.y(), reached.x(), cur.get(i).y(), cur.get(i).x());
                    assertTrue(gap < 0.5, "Vehicle " + i + " changed trips " + gap + " m away from its destination");
                    destination[i] = d;
                    newTrips++;
                }
            }
        }
        assertTrue(newTrips > 150, "Expected most vehicles to complete trips, got " + newTrips);
        assertEquals(newTrips, gen.getTripsCompleted());
    }

    @Test
    @Timeout(60)
    void vehiclesTravelAcrossNeighbourhoods() {
        // Regression: the random walk left vehicles a median ~100 m from their start after 5 minutes.
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 1_000, 0, 42L);
        List<PositionUpdate> start = gen.tick(0.0);
        List<PositionUpdate> cur = start;
        for (int tick = 0; tick < 600; tick++) cur = gen.tick(1.0);   // 10 simulated minutes
        double[] moved = new double[start.size()];
        for (int i = 0; i < moved.length; i++) {
            moved[i] = GeoUtils.haversineMeters(start.get(i).y(), start.get(i).x(), cur.get(i).y(), cur.get(i).x());
        }
        Arrays.sort(moved);
        assertTrue(moved[moved.length / 2] > 1_500, "Median displacement after 10 min: " + moved[moved.length / 2] + " m");
    }

    @Test
    @Timeout(60)
    void naturalRouteThroughAZoneProducesEnterAndExitEvents() {
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 50, 0, 5L);
        gen.tick(0.0);
        // A small zone around a node in the middle of vehicle 0's planned route (not its start or destination).
        long[] path = gen.plannedPathNodes(0);
        assertTrue(path.length > 20, "Route too short for the test");
        RoadGraph.Node mid = graph.getNode(path[path.length / 2]);
        double r = 0.0004; // about 30 x 45 m
        List<Position> polygon = List.of(new Position(mid.x() - r, mid.y() - r, 0), new Position(mid.x() + r, mid.y() - r, 0),
                new Position(mid.x() + r, mid.y() + r, 0), new Position(mid.x() - r, mid.y() + r, 0));
        BoundingBox world = new BoundingBox(-122.5, 47.5, -122.2, 47.8);
        CowQuadtree qt = new CowQuadtree(world);
        HamtIndex hamt = new HamtIndex();
        GeofenceEngine engine = new GeofenceEngine(new CowQuadtree[]{qt}, new HamtIndex[]{hamt},
                List.of(new GeofenceEngine.Zone("mid-route", polygon, new BoundingBox(mid.x() - r, mid.y() - r, mid.x() + r, mid.y() + r))));
        List<ZoneEvent> events = new ArrayList<>();
        for (int tick = 0; tick < 3_000 && events.stream().noneMatch(e -> e.vehicleId() == 0 && e.type() == ZoneEvent.EventType.EXIT); tick++) {
            for (PositionUpdate u : gen.tick(0.5)) {
                Position old = hamt.get(u.vehicleId());
                if (old == null) qt.insert(u.vehicleId(), u.x(), u.y()); else qt.update(u.vehicleId(), old.x(), old.y(), u.x(), u.y());
                hamt.put(u.vehicleId(), new Position(u.x(), u.y(), u.timestamp()));
            }
            qt.publish();
            hamt.publish();
            events.addAll(engine.check());
        }
        List<ZoneEvent.EventType> mine = events.stream().filter(e -> e.vehicleId() == 0).map(ZoneEvent::type).toList();
        assertTrue(mine.size() >= 2, "Vehicle 0 should enter and leave the zone on its route, got " + mine);
        assertEquals(ZoneEvent.EventType.ENTER, mine.get(0));
        assertEquals(ZoneEvent.EventType.EXIT, mine.get(1));
    }

    @Test
    @Timeout(60)
    void redirectedVehicleDrivesToTheTargetWithoutJumping() {
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 10, 0, 9L);
        List<PositionUpdate> prev = gen.tick(0.5);
        // Target: a road node 0.8-1.2 km from vehicle 3.
        PositionUpdate p = prev.get(3);
        RoadGraph.Node target = graph.getAllNodes().stream()
                .sorted(Comparator.comparingLong(RoadGraph.Node::id))
                .filter(n -> {
                    double d = GeoUtils.haversineMeters(p.y(), p.x(), n.y(), n.x());
                    return d > 800 && d < 1_200;
                })
                .findFirst().orElseThrow();
        assertTrue(gen.redirect(3, target.id()));
        boolean arrived = false;
        for (int tick = 0; tick < 2_400 && !arrived; tick++) {
            List<PositionUpdate> cur = gen.tick(0.5);
            PositionUpdate b = cur.get(3), a = prev.get(3);
            assertTrue(GeoUtils.haversineMeters(a.y(), a.x(), b.y(), b.x()) <= maxLimitMps * 0.5 + 1e-6);
            arrived = GeoUtils.haversineMeters(target.y(), target.x(), b.y(), b.x()) < 0.5;
            prev = cur;
        }
        assertTrue(arrived, "Redirected vehicle never reached its target");
        assertFalse(gen.redirect(3, -12345L), "Unknown nodes are rejected");
    }

    @Test
    @Timeout(120)
    void tenThousandVehiclesRouteAndTickWithinBudget() {
        GraphVehicleGenerator gen = new GraphVehicleGenerator(graph, 10_000, 0, 42L);
        long t0 = System.nanoTime();
        gen.tick(0.2);                         // computes all 10,000 initial routes (in parallel)
        long firstMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        for (int k = 0; k < 100; k++) gen.tick(1.0);
        double steadyMs = (System.nanoTime() - t1) / 1e6 / 100;
        System.out.printf("[CITY 10000] first tick %d ms, steady tick %.2f ms%n", firstMs, steadyMs);
        // Generous bounds (CI machines vary); measured ~1.5 s and ~4 ms on an Apple M3.
        assertTrue(firstMs < 20_000, "Initial routing took " + firstMs + " ms");
        assertTrue(steadyMs < 100, "Steady tick took " + steadyMs + " ms");
    }
}
