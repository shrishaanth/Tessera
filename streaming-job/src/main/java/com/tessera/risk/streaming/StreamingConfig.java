package com.tessera.risk.streaming;

import java.io.Serializable;

/**
 * Tunables for the streaming job, every one overridable by environment variable
 * (NFR-3) so a demonstration can be reshaped without a rebuild.
 *
 * <p>Serializable because the HBase connection settings are read inside closures
 * that Spark ships to executors.
 *
 * @param sparkMaster          Spark master URL; {@code local[*]} runs in-process
 * @param bootstrapServers     Kafka bootstrap servers
 * @param telemetryTopic       source topic carrying {@code TelemetryEvent} JSON
 * @param alertTopic           sink topic carrying {@code RiskAlert} JSON
 * @param startingOffsets      {@code latest} or {@code earliest} on a cold start
 * @param maxOffsetsPerTrigger ceiling on records per micro-batch; bounds catch-up
 * @param hbaseZkQuorum        ZooKeeper hosts HBase registers itself in
 * @param hbaseZkPort          ZooKeeper client port
 * @param checkpointDir        Structured Streaming checkpoint location
 * @param windowMinutes        width of the aggregation window
 * @param slideMinutes         how far the window advances each step
 * @param watermarkSeconds     lateness tolerated before window state is dropped
 * @param triggerSeconds       micro-batch interval
 * @param alertScoreThreshold  risk score at or above which an alert is raised
 * @param alertIncidentFloor   incident count that raises an alert regardless of score
 * @param alertMinReadings     readings a window needs before its score may alert
 * @param alertCooldownSeconds minimum gap between two alerts for the same vehicle
 */
public record StreamingConfig(
        String sparkMaster,
        String bootstrapServers,
        String telemetryTopic,
        String alertTopic,
        String startingOffsets,
        long maxOffsetsPerTrigger,
        String hbaseZkQuorum,
        String hbaseZkPort,
        String checkpointDir,
        int windowMinutes,
        int slideMinutes,
        int watermarkSeconds,
        int triggerSeconds,
        double alertScoreThreshold,
        long alertIncidentFloor,
        long alertMinReadings,
        int alertCooldownSeconds) implements Serializable {

    public static StreamingConfig fromEnvironment() {
        return new StreamingConfig(
                // local[*,4], not local[*]: in local mode Spark hardcodes
                // maxTaskFailures to 1 and ignores spark.task.maxFailures entirely.
                // Only the local[N,F] form sets it. Without the F, a single transient
                // HBase error — a region being reassigned, say — fails a task, which
                // fails the batch, which terminates the streaming query outright.
                envString("SPARK_MASTER_URL", "local[*,4]"),
                envString("KAFKA_BOOTSTRAP_SERVERS", "localhost:29092"),
                envString("TESSERA_TELEMETRY_TOPIC", "vehicle.telemetry"),
                envString("TESSERA_ALERT_TOPIC", "vehicle.alerts"),
                envString("TESSERA_STARTING_OFFSETS", "latest"),
                // Roughly 80 seconds of telemetry for a 24-vehicle fleet. This is a
                // backpressure setting, not a throughput one: it caps how much
                // backlog a single batch may swallow after a restart. Left high, the
                // first batch takes the whole topic at once — one run produced 16,000
                // window rows in batch 0 and drove standalone HBase into a pause long
                // enough to expire its ZooKeeper session, which aborts the region
                // server. Catch-up still runs about eight times faster than the
                // producer emits, so a backlog drains quickly regardless.
                envLong("TESSERA_MAX_OFFSETS_PER_TRIGGER", 2_000L),
                envString("TESSERA_HBASE_ZK", "localhost"),
                envString("TESSERA_HBASE_ZK_PORT", "2181"),
                envString("TESSERA_CHECKPOINT_DIR", "/tmp/tessera/checkpoints/risk-windows"),
                envInt("TESSERA_WINDOW_MINUTES", 5),
                envInt("TESSERA_SLIDE_MINUTES", 1),
                envInt("TESSERA_WATERMARK_SECONDS", 120),
                envInt("TESSERA_TRIGGER_SECONDS", 10),
                envDouble("TESSERA_ALERT_SCORE_THRESHOLD", 65.0),
                envLong("TESSERA_ALERT_INCIDENT_FLOOR", 1L),
                // One minute of telemetry at the default tick rate. The newest
                // window is also the emptiest one, and a rate computed over a
                // handful of readings swings wildly: two hard brakes in the first
                // twenty seconds of a window is a rate of 0.1 and would clear the
                // score threshold on evidence nobody would act on.
                envLong("TESSERA_ALERT_MIN_READINGS", 60L),
                envInt("TESSERA_ALERT_COOLDOWN_SECONDS", 180));
    }

    /** Spark's duration syntax for the window width, e.g. {@code "5 minutes"}. */
    public String windowDuration() {
        return windowMinutes + " minutes";
    }

    /** Spark's duration syntax for the slide interval. */
    public String slideDuration() {
        return slideMinutes + " minutes";
    }

    /** Spark's duration syntax for the watermark delay. */
    public String watermarkDelay() {
        return watermarkSeconds + " seconds";
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String envString(String key, String fallback) {
        String v = env(key);
        return v == null ? fallback : v;
    }

    private static int envInt(String key, int fallback) {
        String v = env(key);
        return v == null ? fallback : Integer.parseInt(v);
    }

    private static long envLong(String key, long fallback) {
        String v = env(key);
        return v == null ? fallback : Long.parseLong(v);
    }

    private static double envDouble(String key, double fallback) {
        String v = env(key);
        return v == null ? fallback : Double.parseDouble(v);
    }
}
