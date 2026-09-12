package com.tessera.fleet.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.tessera.fleet.dataset.FleetDataset;
import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.support.TestFixtures;

class DatasetPositionSourceTest {

    private static FleetDataset dataset;

    @BeforeAll
    static void load() throws Exception {
        dataset = FleetDataset.load(
                new DefaultResourceLoader().getResource(TestFixtures.DATASET_RESOURCE));
    }

    @Test
    void emitsOneReportPerVehicleWithStableIds() {
        DatasetPositionSource src = new DatasetPositionSource(dataset, 0L);
        List<PositionReport> frame = src.frame(0, 1_000L);

        assertThat(frame).hasSize(dataset.vehicleCount());
        assertThat(frame).allSatisfy(r -> {
            assertThat(r.vehicleId()).startsWith("SIM-");
            assertThat(r.epochMillis()).isEqualTo(1_000L);
            assertThat(r.latitude()).isBetween(-90.0, 90.0);
            assertThat(r.longitude()).isBetween(-180.0, 180.0);
        });
        assertThat(frame.stream().map(PositionReport::vehicleId).distinct())
                .hasSize(dataset.vehicleCount());
    }

    @Test
    void mapsWallClockOntoDatasetTicksAndWrapsAtTheEnd() {
        DatasetPositionSource src = new DatasetPositionSource(dataset, 10_000L);

        assertThat(src.tickAt(10_000L)).isZero();
        assertThat(src.tickAt(10_000L + 5_000L)).isEqualTo(5);
        assertThat(src.tickAt(9_000L)).isZero(); // before the origin, clamped
        // One full track later we are back at tick 0 — the loop is seamless.
        long oneLoop = (long) dataset.tickCount() * dataset.tickMillis();
        assertThat(src.tickAt(10_000L + oneLoop)).isZero();
        assertThat(src.tickAt(10_000L + oneLoop + 7_000L)).isEqualTo(7);
    }

    @Test
    void pollReturnsNothingUntilTheTrackAdvances() {
        DatasetPositionSource src = new DatasetPositionSource(dataset);
        assertThat(src.poll()).hasSize(dataset.vehicleCount());
        assertThat(src.poll()).isEmpty(); // same tick — no new data yet
    }

    @Test
    void disclosesThatPositionsAreSimulated() {
        DatasetPositionSource src = new DatasetPositionSource(dataset);
        assertThat(src.isSubstitute()).isTrue();
        assertThat(src.disclosure()).contains("SIMULATED");
        assertThat(src.id()).isEqualTo("dataset");
    }
}
