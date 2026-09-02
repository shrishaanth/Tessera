package com.tessera.fleet.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.support.TestFixtures;

class WriteBehindServiceTest {

    private WriteBehindService svc;

    private static FleetProperties propsWith(int capacity, int batch, long flush) {
        FleetProperties base = TestFixtures.fleetProperties();
        return new FleetProperties(base.offlineAfterSeconds(), base.ingestPollMillis(),
                base.broadcastMillis(), base.nearest(), base.positionSource(), base.simulator(),
                base.gtfs(), base.roadGraphResource(), base.geofence(),
                new FleetProperties.Durable("in-memory", capacity, batch, flush,
                        base.durable().datasource()),
                base.users());
    }

    private static PositionRecord pos(String id) {
        return new PositionRecord(id, 42.36, -71.06, 20, 90, System.currentTimeMillis());
    }

    @AfterEach
    void stop() {
        if (svc != null) {
            svc.stop();
        }
    }

    @Test
    void drainsQueuedPositionsIntoTheStoreInBatches() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        svc = new WriteBehindService(store, propsWith(10_000, 100, 100));
        svc.start();

        for (int i = 0; i < 550; i++) {
            svc.offerPosition(pos("V" + i));
        }
        assertThat(svc.awaitDrained(3000)).isTrue();
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(store.positionCount()).isEqualTo(550));
        assertThat(svc.stats().writtenPositions()).isEqualTo(550);
        assertThat(svc.stats().droppedFull()).isZero();
    }

    @Test
    void dropsAndCountsWhenTheQueueIsFullNeverBlockingTheProducer() {
        // A store that blocks forever so the consumer cannot drain.
        AtomicBoolean release = new AtomicBoolean(false);
        DurableStore blocking = new InMemoryDurableStore() {
            @Override
            public void savePositions(List<PositionRecord> batch) {
                while (!release.get()) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                super.savePositions(batch);
            }
        };
        svc = new WriteBehindService(blocking, propsWith(100, 50, 50));
        svc.start();

        long start = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            svc.offerPosition(pos("V" + i));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(1000L);           // producer never blocked
        assertThat(svc.stats().droppedFull()).isGreaterThan(0);
        release.set(true);
    }

    @Test
    void aFailingStoreDegradesHealthAndDropsAfterRetriesWithoutStopping() {
        AtomicInteger calls = new AtomicInteger();
        DurableStore flaky = new InMemoryDurableStore() {
            @Override
            public void savePositions(List<PositionRecord> batch) {
                calls.incrementAndGet();
                throw new RuntimeException("db down");
            }
        };
        svc = new WriteBehindService(flaky, propsWith(10_000, 100, 50));
        svc.start();

        svc.offerPosition(pos("A"));
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(svc.stats().healthy()).isFalse());
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(svc.stats().droppedError()).isGreaterThan(0));
        assertThat(calls.get()).isGreaterThanOrEqualTo(3); // retried before dropping

        // still alive: a later offer is still accepted
        svc.offerPosition(pos("B"));
        assertThat(svc.isRunning()).isTrue();
    }

    @Test
    void geofenceEventsAreDrainedToo() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        svc = new WriteBehindService(store, propsWith(10_000, 100, 50));
        svc.start();

        svc.offerGeofenceEvent(GeofenceEventRecord.enter("V1", "SITE-A", 1000));
        svc.offerGeofenceEvent(GeofenceEventRecord.exit("V1", "SITE-A", 5000, 4));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(store.recentGeofenceEvents("V1", null, 10)).hasSize(2));
    }
}
