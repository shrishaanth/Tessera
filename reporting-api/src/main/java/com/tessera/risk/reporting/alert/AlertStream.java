package com.tessera.risk.reporting.alert;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tessera.risk.common.model.RiskAlert;
import com.tessera.risk.reporting.ReportingProperties;

/**
 * Consumes {@code vehicle.alerts} and hands each alert to the history and the
 * broadcaster (FR-4.2).
 *
 * <h2>Why a thread rather than a listener container</h2>
 * One topic, one partition, one consumer, no offset commits that matter. A
 * {@code @KafkaListener} would bring a container, a rebalance protocol and an
 * error-handling strategy to manage, all of which would be configuration standing
 * in for about thirty lines.
 *
 * <h2>Why offsets are not committed</h2>
 * The consumer starts from the latest offset with a random group id, so every
 * restart begins from now. That is deliberate: alerts are notifications, and
 * replaying an hour of stale ones into a dashboard on restart would be worse than
 * useless — an operator would be shown a backlog of situations that have already
 * resolved. The bounded history covers the only gap that matters, a client
 * connecting slightly late.
 */
@Component
public class AlertStream {

    private static final Logger log = LoggerFactory.getLogger(AlertStream.class);

    private final ReportingProperties properties;
    private final AlertHistory history;
    private final AlertBroadcaster broadcaster;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong malformed = new AtomicLong();

    private KafkaConsumer<String, String> consumer;
    private Thread thread;

    public AlertStream(ReportingProperties properties, AlertHistory history,
                       AlertBroadcaster broadcaster) {
        this.properties = properties;
        this.history = history;
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    void start() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafkaBootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // A fresh group every start, so this consumer never inherits another run's
        // position and never competes with a second instance for partitions.
        config.put(ConsumerConfig.GROUP_ID_CONFIG,
                "tessera-reporting-" + java.util.UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        consumer = new KafkaConsumer<>(config);
        running.set(true);
        thread = new Thread(this::poll, "alert-stream");
        thread.setDaemon(true);
        thread.start();
        log.info("Consuming alerts from {} on {}",
                properties.getAlertTopic(), properties.getKafkaBootstrapServers());
    }

    private void poll() {
        try {
            consumer.subscribe(List.of(properties.getAlertTopic()));
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    accept(record.value());
                }
            }
        } catch (WakeupException e) {
            // The documented way to break out of poll() from another thread.
            log.debug("Alert consumer woken for shutdown");
        } catch (RuntimeException e) {
            // The dashboard's map and history come from HBase and must keep working
            // even if the alert feed dies, so this is logged rather than propagated
            // into a failed application context.
            log.error("Alert consumer stopped: {}", e.getMessage(), e);
        } finally {
            consumer.close();
        }
    }

    private void accept(String payload) {
        try {
            RiskAlert alert = RiskAlert.fromJson(payload);
            history.add(alert);
            broadcaster.broadcast(alert);
            consumed.incrementAndGet();
        } catch (RuntimeException e) {
            // One unparseable message must not take down the feed. Counted so the
            // problem is visible rather than merely survived.
            malformed.incrementAndGet();
            log.warn("Discarding malformed alert: {}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (consumer != null) {
            consumer.wakeup();
        }
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public long consumedCount() {
        return consumed.get();
    }

    public long malformedCount() {
        return malformed.get();
    }

    public boolean isRunning() {
        return running.get() && thread != null && thread.isAlive();
    }
}
