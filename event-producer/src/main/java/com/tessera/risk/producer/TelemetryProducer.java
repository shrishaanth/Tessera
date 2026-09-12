package com.tessera.risk.producer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tessera.risk.common.model.TelemetryEvent;

/**
 * Publishes telemetry readings to Kafka.
 *
 * <p>Records are keyed by {@code vehicleId}, which places every reading for a
 * vehicle on the same partition and therefore in order. The per-vehicle windowed
 * aggregation downstream depends on that ordering; keying by anything else, or
 * not at all, would scatter a vehicle's readings across partitions and make its
 * deceleration history meaningless.
 */
final class TelemetryProducer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TelemetryProducer.class);

    private final Producer<String, String> producer;
    private final String topic;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    TelemetryProducer(String bootstrapServers, String topic) {
        this.topic = topic;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // Durability over raw throughput: this is a demonstration pipeline, and a
        // silently dropped reading would corrupt a vehicle's deceleration history.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "tessera-event-producer");
        this.producer = new KafkaProducer<>(props);
        log.info("Kafka producer ready: bootstrap={} topic={}", bootstrapServers, topic);
    }

    /** Publish one tick's readings. Delivery failures are counted, never fatal. */
    void publish(List<TelemetryEvent> batch) {
        for (TelemetryEvent event : batch) {
            producer.send(
                    new ProducerRecord<>(topic, event.vehicleId(), event.toJson()),
                    (metadata, exception) -> {
                        if (exception != null) {
                            if (failed.incrementAndGet() % 100 == 1) {
                                log.warn("Telemetry delivery failed: {}", exception.toString());
                            }
                        } else {
                            sent.incrementAndGet();
                        }
                    });
        }
    }

    long sentCount() {
        return sent.get();
    }

    long failedCount() {
        return failed.get();
    }

    @Override
    public void close() {
        producer.flush();
        producer.close(Duration.ofSeconds(10));
        log.info("Kafka producer closed: sent={} failed={}", sent.get(), failed.get());
    }
}
