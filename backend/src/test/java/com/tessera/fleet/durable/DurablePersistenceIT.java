package com.tessera.fleet.durable;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.support.AbstractRedisIntegrationTest;

/**
 * A position stream reaches the durable store through the bounded write-behind
 * queue (SRS §3.1) while the live layer keeps running independently (SRS §2.5).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "tessera.ingest-poll-millis=3600000",
        "tessera.broadcast-millis=3600000",
        "tessera.durable.flush-millis=100"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DurablePersistenceIT extends AbstractRedisIntegrationTest {

    @Autowired WriteBehindService writeBehind;
    @Autowired DurableStore durableStore;
    @Autowired LiveFleetService liveFleet;

    @Test
    void positionsAreWrittenBehindToTheDurableStoreWithoutBlockingTheLiveLayer() {
        List<PositionRecord> batch = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 50; i++) {
            String id = "PERSIST-" + (i % 5);
            double lat = 42.3600 + i * 1e-4;
            double lon = -71.0600 + i * 1e-4;
            long ts = t0 + i * 1000L;
            // Live layer (Redis) and write-behind queue are fed the same fixes,
            // exactly as the ingestion loop does.
            liveFleet.applyReport(new PositionReport(id, id, lat, lon, 0, 20, ts));
            batch.add(new PositionRecord(id, lat, lon, 20, 0, ts));
        }
        batch.forEach(writeBehind::offerPosition);

        assertThat(writeBehind.awaitDrained(3000)).isTrue();

        assertThat(durableStore).isInstanceOf(InMemoryDurableStore.class);
        assertThat(durableStore.positionCount()).isGreaterThanOrEqualTo(50L);
        assertThat(durableStore.trajectory("PERSIST-0", 0, Long.MAX_VALUE)).isNotEmpty();

        WriteBehindService.Stats stats = writeBehind.stats();
        assertThat(stats.healthy()).isTrue();
        assertThat(stats.writtenPositions()).isGreaterThanOrEqualTo(50L);
        assertThat(stats.droppedError()).isZero();

        // The live layer is fully populated regardless of the durable path.
        assertThat(liveFleet.allVehicles()).hasSize(5);
    }
}
