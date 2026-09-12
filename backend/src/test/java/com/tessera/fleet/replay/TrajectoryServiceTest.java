package com.tessera.fleet.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.durable.InMemoryDurableStore;
import com.tessera.fleet.durable.PositionRecord;
import com.tessera.fleet.replay.TrajectoryService.Trajectory;
import com.tessera.fleet.support.TestFixtures;

class TrajectoryServiceTest {

    private FleetProperties propsWithMaxPoints(int maxPoints) {
        FleetProperties b = TestFixtures.fleetProperties();
        return new FleetProperties(b.offlineAfterSeconds(), b.ingestPollMillis(), b.broadcastMillis(),
                b.positionSource(), b.dataset(), b.gtfs(), b.roadGraphResource(), b.geofence(),
                b.durable(), b.geocoding(),
                new FleetProperties.Replay(maxPoints), b.users());
    }

    private static InMemoryDurableStore storeWith(String vehicle, long startMs, int count, long stepMs) {
        InMemoryDurableStore store = new InMemoryDurableStore();
        List<PositionRecord> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(new PositionRecord(vehicle, 42.35 + i * 1e-5, -71.06 + i * 1e-5,
                    20, 90, startMs + i * stepMs));
        }
        store.savePositions(batch);
        return store;
    }

    @Test
    void returnsTheDaysPointsOrderedByTime() {
        long dayStart = LocalDate.of(2026, 3, 10).atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli();
        InMemoryDurableStore store = storeWith("CAR-1", dayStart + 3_600_000, 5, 600_000);
        // add one point on the previous day and one on the next
        store.savePositions(List.of(
                new PositionRecord("CAR-1", 1, 1, 0, 0, dayStart - 60_000),
                new PositionRecord("CAR-1", 1, 1, 0, 0, dayStart + 86_400_000L + 60_000)));

        TrajectoryService svc = new TrajectoryService(store, propsWithMaxPoints(3000));
        Trajectory t = svc.forDay("CAR-1", LocalDate.of(2026, 3, 10));

        assertThat(t.totalPoints()).isEqualTo(5);
        assertThat(t.sampled()).isFalse();
        assertThat(t.points()).extracting(TrajectoryService.TrajectoryPoint::epochMillis)
                .isSorted();
    }

    @Test
    void filtersByVehicle() {
        InMemoryDurableStore store = storeWith("CAR-1", 1_000_000, 4, 1000);
        store.savePositions(List.of(new PositionRecord("CAR-2", 1, 1, 0, 0, 1_000_500)));
        TrajectoryService svc = new TrajectoryService(store, propsWithMaxPoints(3000));

        assertThat(svc.between("CAR-1", 0, 2_000_000).totalPoints()).isEqualTo(4);
        assertThat(svc.between("CAR-2", 0, 2_000_000).totalPoints()).isEqualTo(1);
    }

    @Test
    void strideSamplesLongPathsButKeepsTheFirstAndLastFix() {
        InMemoryDurableStore store = storeWith("CAR-1", 0, 5000, 1000);
        TrajectoryService svc = new TrajectoryService(store, propsWithMaxPoints(500));

        Trajectory t = svc.between("CAR-1", 0, 6_000_000);
        assertThat(t.totalPoints()).isEqualTo(5000);
        assertThat(t.sampled()).isTrue();
        assertThat(t.points().size()).isLessThanOrEqualTo(501);
        assertThat(t.points().get(0).epochMillis()).isZero();
        assertThat(t.points().get(t.points().size() - 1).epochMillis()).isEqualTo(4_999_000);
    }
}
