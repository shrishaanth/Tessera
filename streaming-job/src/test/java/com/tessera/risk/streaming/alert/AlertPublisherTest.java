package com.tessera.risk.streaming.alert;

import java.util.List;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.model.RiskAlert;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.streaming.StreamingConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for alert delivery and, mostly, for alert suppression. */
class AlertPublisherTest {

    private static final long BASE = 1_767_225_600_000L;
    private static final int COOLDOWN_SECONDS = 180;

    @Test
    @DisplayName("an alert reaches the topic keyed by vehicle")
    void alertIsPublishedKeyedByVehicle() {
        MockProducer<String, String> producer = mockProducer();
        try (AlertPublisher publisher = new AlertPublisher(config(), producer)) {
            publisher.publish(List.of(alert("VEH-001", BASE)), BASE);
        }

        assertThat(producer.history()).hasSize(1);
        ProducerRecord<String, String> record = producer.history().get(0);
        assertThat(record.topic()).isEqualTo("vehicle.alerts");
        assertThat(record.key()).isEqualTo("VEH-001");
        // Round-tripping proves the payload a consumer receives is actually
        // readable, which asserting on the raw string would not.
        assertThat(RiskAlert.fromJson(record.value()).vehicleId()).isEqualTo("VEH-001");
    }

    @Test
    @DisplayName("a vehicle still over the threshold does not alert every batch")
    void repeatAlertsAreSuppressed() {
        MockProducer<String, String> producer = mockProducer();
        try (AlertPublisher publisher = new AlertPublisher(config(), producer)) {
            // Micro-batches ten seconds apart, the vehicle risky throughout.
            for (int batch = 0; batch < 6; batch++) {
                publisher.publish(List.of(alert("VEH-001", BASE)), BASE + batch * 10_000L);
            }
        }

        assertThat(producer.history()).hasSize(1);
    }

    @Test
    @DisplayName("the same vehicle alerts again once the cooldown has passed")
    void alertResumesAfterCooldown() {
        MockProducer<String, String> producer = mockProducer();
        try (AlertPublisher publisher = new AlertPublisher(config(), producer)) {
            publisher.publish(List.of(alert("VEH-001", BASE)), BASE);
            publisher.publish(List.of(alert("VEH-001", BASE)),
                    BASE + COOLDOWN_SECONDS * 1000L);
        }

        assertThat(producer.history()).hasSize(2);
    }

    @Test
    @DisplayName("one vehicle's cooldown never hides another vehicle")
    void cooldownIsPerVehicle() {
        MockProducer<String, String> producer = mockProducer();
        try (AlertPublisher publisher = new AlertPublisher(config(), producer)) {
            publisher.publish(List.of(alert("VEH-001", BASE)), BASE);
            List<RiskAlert> sent = publisher.publish(
                    List.of(alert("VEH-001", BASE), alert("VEH-002", BASE)), BASE + 10_000L);

            assertThat(sent).extracting(RiskAlert::vehicleId).containsExactly("VEH-002");
        }

        assertThat(producer.history()).hasSize(2);
    }

    private static MockProducer<String, String> mockProducer() {
        return new MockProducer<>(true, new StringSerializer(), new StringSerializer());
    }

    private static StreamingConfig config() {
        return new StreamingConfig(
                "local[1]", "localhost:29092", "vehicle.telemetry", "vehicle.alerts",
                "latest", 50_000L, "localhost", "2181", "target/test-checkpoints",
                5, 1, 120, 10, "target/no-such-model", 65.0, 0.28, 1L, 60L, COOLDOWN_SECONDS);
    }

    private static RiskAlert alert(String vehicleId, long windowStart) {
        return new RiskAlert(vehicleId, "DRV-001", RiskTier.RISKY, windowStart,
                windowStart + 300_000L, 78.0, 12, 3, 1, 300, "test", windowStart);
    }
}
