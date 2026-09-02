package com.tessera.fleet.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.model.PositionReport;
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
}
