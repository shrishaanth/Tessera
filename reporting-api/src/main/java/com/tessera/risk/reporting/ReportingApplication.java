package com.tessera.risk.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Read-only service over the HBase risk store (FR-5.1).
 *
 * <p>It computes nothing. Every number it serves was produced by the streaming job
 * and written to HBase, which is the point of having an analytical store: the read
 * path stays a lookup, so the dashboard's latency does not depend on Spark being
 * healthy, and a query cannot accidentally disagree with what the pipeline decided.
 *
 * <p>The one thing it does not read from HBase is the alert feed. Alerts are
 * notifications, not state — an operator needs them the moment they happen, and
 * polling a table for rows that may appear seconds from now is the wrong shape.
 * They arrive from {@code vehicle.alerts} and are pushed straight to connected
 * clients (FR-4.2).
 */
@SpringBootApplication
@EnableConfigurationProperties(ReportingProperties.class)
public class ReportingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingApplication.class, args);
    }
}
