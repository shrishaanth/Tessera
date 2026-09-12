package com.tessera.risk.streaming;

import org.apache.spark.sql.SparkSession;

/**
 * A single local Spark session shared by every test in the module.
 *
 * <p>Starting a session costs seconds, and Spark permits only one active session
 * per JVM, so each test class creating and stopping its own would be both slow and
 * a source of cross-class failures. It is deliberately never stopped: the JVM
 * exiting at the end of the Surefire run does that, and a shutdown ordered from
 * one test class would break whichever class happened to run next.
 */
final class SparkTestSupport {

    private static volatile SparkSession session;

    private SparkTestSupport() {
    }

    static synchronized SparkSession session() {
        if (session == null) {
            session = SparkSession.builder()
                    .appName("tessera-risk-tests")
                    .master("local[2]")
                    .config("spark.sql.session.timeZone", "UTC")
                    // Two partitions, not the default 200: these datasets are tiny
                    // and 200 empty tasks per shuffle turn a one-second test into a
                    // thirty-second one.
                    .config("spark.sql.shuffle.partitions", "2")
                    .config("spark.ui.enabled", "false")
                    .getOrCreate();
            session.sparkContext().setLogLevel("WARN");
        }
        return session;
    }

    /** Test configuration: the windowing matches production, the endpoints do not. */
    static StreamingConfig config() {
        return new StreamingConfig(
                "local[2]", "localhost:29092", "vehicle.telemetry", "vehicle.alerts",
                "latest", 50_000L, "localhost", "2181", "target/test-checkpoints",
                5, 1, 120, 10, 65.0, 1L, 60L, 180);
    }
}
