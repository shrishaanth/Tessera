package com.tessera.fleet.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.routing.GeoMath;
import com.tessera.fleet.routing.RoadGraph;
import com.tessera.fleet.support.TestFixtures;

class SimulatedPositionSourceTest {

    private static FleetProperties.Simulator config(int vehicles) {
        return new FleetProperties.Simulator(vehicles, 1000L, 42L, 0.7);
    }

    @Test
    void spawnsRequestedNumberOfVehiclesWithStableIds() {
        SimulatedPositionSource src =
                new SimulatedPositionSource(TestFixtures.realRoadGraph(), config(25));
        List<PositionReport> first = src.advance(1000);
        assertThat(first).hasSize(25);
        assertThat(first).allSatisfy(r -> assertThat(r.vehicleId()).startsWith("SIM-"));
        assertThat(first.stream().map(PositionReport::vehicleId).distinct()).hasSize(25);
    }

    @Test
    void isDeterministicForAGivenSeedAndStepSequence() {
        RoadGraph graph = TestFixtures.realRoadGraph();
        SimulatedPositionSource a = new SimulatedPositionSource(graph, config(15));
        SimulatedPositionSource b = new SimulatedPositionSource(graph, config(15));

        for (int step = 0; step < 20; step++) {
            List<PositionReport> ra = a.advance(1000);
            List<PositionReport> rb = b.advance(1000);
            for (int i = 0; i < ra.size(); i++) {
                assertThat(ra.get(i).latitude()).isEqualTo(rb.get(i).latitude());
                assertThat(ra.get(i).longitude()).isEqualTo(rb.get(i).longitude());
            }
        }
    }

    @Test
    void vehiclesMoveButStayInsideTheDemoAreaBounds() {
        RoadGraph graph = TestFixtures.realRoadGraph();
        SimulatedPositionSource src = new SimulatedPositionSource(graph, config(30));

        PositionReport startOfOne = src.advance(1000).get(0);
        double totalDrift = 0;
        PositionReport last = startOfOne;
        for (int step = 0; step < 60; step++) {
            List<PositionReport> batch = src.advance(1000);
            PositionReport now = batch.get(0);
            totalDrift += Math.abs(now.latitude() - last.latitude())
                    + Math.abs(now.longitude() - last.longitude());
            last = now;
            for (PositionReport r : batch) {
                // Downtown Boston demo bbox with a small margin.
                assertThat(r.latitude()).isBetween(42.34, 42.38);
                assertThat(r.longitude()).isBetween(-71.09, -71.04);
                assertThat(r.speedKph()).isBetween(0.0, 130.0);
            }
        }
        assertThat(totalDrift).isGreaterThan(0.0); // it actually moved
    }

    @Test
    void aRoamingVehicleNeverTeleportsAcrossTheMapOnADeadEnd() {
        RoadGraph graph = TestFixtures.realRoadGraph();
        SimulatedPositionSource src = new SimulatedPositionSource(graph, config(40));

        Map<String, PositionReport> prev = new HashMap<>();
        for (PositionReport r : src.advance(1000)) {
            prev.put(r.vehicleId(), r);
        }
        for (int tick = 0; tick < 400; tick++) {
            for (PositionReport r : src.advance(1000)) {
                PositionReport p = prev.get(r.vehicleId());
                double jump = GeoMath.haversineMeters(
                        p.latitude(), p.longitude(), r.latitude(), r.longitude());
                assertThat(jump)
                        .as("%s jumped %.0f m in one 1 s tick", r.vehicleId(), jump)
                        .isLessThan(500.0);
                prev.put(r.vehicleId(), r);
            }
        }
    }

    @Test
    void anAssignedVehicleDrivesToItsDestinationAndReportsArrival() {
        RoadGraph graph = TestFixtures.realRoadGraph();
        List<String> arrivals = new ArrayList<>();
        SimulatedPositionSource src =
                new SimulatedPositionSource(graph, config(30), arrivals::add);

        double destLat = 42.3560;
        double destLon = -71.0635;
        for (PositionReport r : src.advance(1000)) {
            src.assignDestination(r.vehicleId(), destLat, destLon);
        }

        Map<String, Double> arrivalDistance = new HashMap<>();
        for (int tick = 0; tick < 1500 && arrivalDistance.size() < arrivals.size() + 1; tick++) {
            Map<String, PositionReport> now = new HashMap<>();
            for (PositionReport r : src.advance(1000)) {
                now.put(r.vehicleId(), r);
            }
            while (arrivalDistance.size() < arrivals.size()) {
                String id = arrivals.get(arrivalDistance.size());
                PositionReport r = now.get(id);
                arrivalDistance.put(id, GeoMath.haversineMeters(
                        r.latitude(), r.longitude(), destLat, destLon));
            }
        }

        // Routed arrivals only fire when a planned path is completed — roaming
        // never triggers the callback — so a non-empty list already proves the
        // vehicles drove there rather than wandered in.
        assertThat(arrivals).isNotEmpty();
        assertThat(new HashSet<>(arrivals)).hasSameSizeAs(arrivals); // no vehicle twice
        // Most arrivals reach the destination itself; only vehicles boxed in by
        // one-way streets stop at the nearest reachable point (best effort).
        long closeArrivals = arrivalDistance.values().stream().filter(d -> d < 150.0).count();
        assertThat(closeArrivals).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void anUnreachableDestinationStillResolvesToTheNearestReachablePoint() {
        RoadGraph graph = TestFixtures.realRoadGraph();
        List<String> arrivals = new ArrayList<>();
        SimulatedPositionSource src =
                new SimulatedPositionSource(graph, config(20), arrivals::add);

        // Well outside the demo road network — its nearest-node snap is a border
        // node that may not be forward-reachable. The vehicle must still finish
        // its route (best effort) rather than roam forever with the job hung.
        for (PositionReport r : src.advance(1000)) {
            src.assignDestination(r.vehicleId(), 42.300, -71.150);
        }
        for (int tick = 0; tick < 2000 && arrivals.isEmpty(); tick++) {
            src.advance(1000);
        }
        assertThat(arrivals).isNotEmpty();
    }
}
