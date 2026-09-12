package com.tessera.risk.streaming.alert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tessera.risk.common.model.RiskAlert;
import com.tessera.risk.streaming.StreamingConfig;

/**
 * Publishes alerts to {@code vehicle.alerts} (FR-4.2).
 *
 * <h2>Why a plain producer, not a Spark Kafka sink</h2>
 * Alerts are tens of records per batch, not millions, and they need something a
 * sink cannot give: memory of what was already sent. Writing them through
 * {@code DataFrame.write().format("kafka")} would spread a handful of rows across
 * executors, start a producer on each, and — because every batch is independent —
 * re-send an alert for the same vehicle every few seconds for as long as it stayed
 * risky. One producer on the driver is both simpler and the only place a cooldown
 * can live.
 *
 * <h2>Why a cooldown matters</h2>
 * A vehicle over the threshold stays over it for minutes. Without suppression the
 * operator gets one alert every micro-batch for the same ongoing situation, which
 * is how alerting systems get muted and then ignored. The cooldown is per vehicle,
 * so a second vehicle going bad is never hidden by the first.
 *
 * <p>The state is a plain map because this object lives on the driver for the life
 * of the query. It is deliberately not checkpointed: after a restart, re-alerting
 * on a vehicle that is still unsafe is the correct behaviour, not a duplicate.
 */
public final class AlertPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AlertPublisher.class);

    private final Producer<String, String> producer;
    private final String topic;
    private final long cooldownMillis;
    private final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    public AlertPublisher(StreamingConfig config) {
        this(config, new KafkaProducer<>(producerProperties(config)));
    }

    /** Visible for tests, which supply a {@code MockProducer}. */
    AlertPublisher(StreamingConfig config, Producer<String, String> producer) {
        this.producer = producer;
        this.topic = config.alertTopic();
        this.cooldownMillis = config.alertCooldownSeconds() * 1000L;
    }

    private static Properties producerProperties(StreamingConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // An alert is worth little once it is stale, so a broker outage should fail
        // it rather than hold it in the buffer and deliver a surprise later.
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "tessera-alert-publisher");
        return props;
    }

    /**
     * Publish the alerts not suppressed by the cooldown.
     *
     * @param now current time in epoch millis, passed in so the cooldown is testable
     * @return the alerts actually sent
     */
    public List<RiskAlert> publish(List<RiskAlert> alerts, long now) {
        List<RiskAlert> sent = new ArrayList<>();
        for (RiskAlert alert : alerts) {
            Long last = lastAlertAt.get(alert.vehicleId());
            if (last != null && now - last < cooldownMillis) {
                continue;
            }
            lastAlertAt.put(alert.vehicleId(), now);
            // Keyed by vehicle so a consumer sees one vehicle's alerts in order.
            producer.send(new ProducerRecord<>(topic, alert.vehicleId(), alert.toJson()),
                    (metadata, exception) -> {
                        if (exception != null) {
                            log.warn("Alert for {} not delivered: {}",
                                    alert.vehicleId(), exception.getMessage());
                        }
                    });
            sent.add(alert);
        }
        if (!sent.isEmpty()) {
            log.info("Published {} alert(s) ({} suppressed by cooldown)",
                    sent.size(), alerts.size() - sent.size());
        }
        return sent;
    }

    @Override
    public void close() {
        producer.close();
    }
}
