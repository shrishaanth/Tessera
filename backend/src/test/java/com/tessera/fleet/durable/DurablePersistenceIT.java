package com.tessera.fleet.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.tessera.fleet.ingestion.IngestionService;
import com.tessera.fleet.support.AbstractRedisIntegrationTest;

/**
 * The simulator's position stream reaches the durable store through the
 * write-behind path (SRS §3.1) while the live layer keeps running (SRS §2.5).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "tessera.simulator.vehicle-count=10",
        "tessera.ingest-poll-millis=200",
        "tessera.broadcast-millis=3600000",
        "tessera.durable.flush-millis=100"
})
// This context runs the ingestion scheduler with real vehicles; close it after
// the class so its background writes don't leak into later IT classes' Redis.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DurablePersistenceIT extends AbstractRedisIntegrationTest {

    @Autowired IngestionService ingestion;
    @Autowired WriteBehindService writeBehind;
    @Autowired DurableStore durableStore;

    @Test
    void positionsAreWrittenBehindToTheDurableStore() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(ingestion.appliedTotal()).isGreaterThan(30L);
            assertThat(durableStore.positionCount()).isGreaterThan(20L);
        });

        WriteBehindService.Stats stats = writeBehind.stats();
        assertThat(stats.healthy()).isTrue();
        assertThat(stats.writtenPositions()).isGreaterThan(0L);
        assertThat(stats.droppedError()).isZero();
        assertThat(durableStore).isInstanceOf(InMemoryDurableStore.class);
    }
}
