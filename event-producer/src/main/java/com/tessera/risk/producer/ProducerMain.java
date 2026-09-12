package com.tessera.risk.producer;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tessera.risk.common.model.EventType;
import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.common.road.RoadNetwork;
import com.tessera.risk.common.road.RoadNetworkLoader;
import com.tessera.risk.simulation.FleetSimulator;
import com.tessera.risk.simulation.SimulatorConfig;

/**
 * Entry point for the live event producer.
 *
 * <p>Drives the fleet simulation at real wall-clock pace and publishes every
 * reading to Kafka. Running at real time rather than as fast as possible is
 * deliberate: the downstream job uses event-time windows and watermarks, and a
 * fire-hose of compressed time would exercise neither the way a real stream does.
 */
public final class ProducerMain {

    private static final Logger log = LoggerFactory.getLogger(ProducerMain.class);

    public static void main(String[] args) throws Exception {
        String bootstrap = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topic = env("TESSERA_TELEMETRY_TOPIC", "vehicle.telemetry");
        String roadResource = env("TESSERA_ROAD_NETWORK", "roadgraph/roadgraph.json");

        SimulatorConfig config = SimulatorConfig.fromEnvironment();
        RoadNetwork network = RoadNetworkLoader.fromClasspath(roadResource);
        FleetSimulator simulator = new FleetSimulator(network, config);

        log.info("Simulating {} vehicles over '{}' ({} nodes, {} segments) at {} ms/tick",
                simulator.vehicleCount(), network.areaName(),
                network.nodeCount(), network.edgeCount(), config.tickMillis());
        log.info("ALL POSITIONS ARE SIMULATED. Vehicles are driven along a real "
                + "OpenStreetMap road network under real posted speed limits and enforced "
                + "physical acceleration and braking limits. No physical vehicles, "
                + "telematics feed or personal data are involved.");

        CountDownLatch shutdown = new CountDownLatch(1);
        try (TelemetryProducer producer = new TelemetryProducer(bootstrap, topic)) {
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown::countDown, "producer-shutdown"));

            Stats stats = new Stats();
            long nextTickAt = System.currentTimeMillis();

            while (shutdown.getCount() > 0) {
                long now = System.currentTimeMillis();
                List<TelemetryEvent> batch = simulator.tick(now);
                producer.publish(batch);
                stats.record(batch);

                if (stats.ticks % 30 == 0) {
                    log.info("tick {} | sent={} failed={} | hardBrakes={} speedViolations={} incidents={}",
                            stats.ticks, producer.sentCount(), producer.failedCount(),
                            stats.hardBrakes, stats.speedViolations, stats.incidents);
                }

                nextTickAt += config.tickMillis();
                long sleepFor = nextTickAt - System.currentTimeMillis();
                if (sleepFor > 0) {
                    if (shutdown.await(sleepFor, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } else {
                    // Fell behind real time; resynchronise rather than accumulate drift.
                    nextTickAt = System.currentTimeMillis();
                }
            }
            log.info("Shutting down after {} ticks", stats.ticks);
        }
    }

    /** Running counts, logged periodically so the stream is observable while it runs. */
    private static final class Stats {
        long ticks;
        long hardBrakes;
        long speedViolations;
        long incidents;

        void record(List<TelemetryEvent> batch) {
            ticks++;
            for (TelemetryEvent e : batch) {
                if (e.eventType() == EventType.HARD_BRAKE) {
                    hardBrakes++;
                } else if (e.eventType() == EventType.SPEED_VIOLATION) {
                    speedViolations++;
                } else if (e.eventType() == EventType.INCIDENT) {
                    incidents++;
                }
            }
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private ProducerMain() { }
}
