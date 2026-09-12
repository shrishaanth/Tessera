package com.tessera.risk.streaming.hbase;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The wide-column data model, and the code that creates it.
 *
 * <h2>Why column families are split this way</h2>
 * A column family is HBase's unit of physical storage: each one is flushed and
 * compacted separately, and a read that touches only one family never reads the
 * others off disk. So families should follow <em>access patterns</em>, not
 * topics. The split here does exactly that. The dashboard's map view wants traffic
 * and behaviour; the alerting and model-comparison views want scores. Keeping them
 * apart means neither read pays for the other.
 *
 * <p>The counter-pressure is that too many families hurt: HBase flushes all
 * families of a region together, so a family that fills slowly gets dragged along
 * by one that fills fast, producing many small files. Two per table is a
 * deliberate middle.
 *
 * <h2>Versions and time to live</h2>
 * Both time-series tables keep a single version per cell. Retaining more would
 * store the time dimension twice, since the row key already carries it, and the
 * second copy would be the one nothing can query by. The TTL bounds the table
 * without an external cleanup job — old windows expire on their own during
 * compaction.
 */
public final class HBaseSchema {

    private static final Logger log = LoggerFactory.getLogger(HBaseSchema.class);

    public static final TableName SEGMENT_METRICS = TableName.valueOf("segment_metrics");
    public static final TableName VEHICLE_RISK = TableName.valueOf("vehicle_risk");
    public static final TableName DRIVER_PROFILE = TableName.valueOf("driver_profile");

    /** segment_metrics: how traffic is flowing over a stretch of road. */
    public static final byte[] CF_TRAFFIC = bytes("cf_traffic");
    /** segment_metrics: unsafe behaviour observed on that stretch. */
    public static final byte[] CF_RISK = bytes("cf_risk");

    /** vehicle_risk: what the vehicle did. */
    public static final byte[] CF_BEHAVIOR = bytes("cf_behavior");
    /** vehicle_risk: what the rules and the model make of it. */
    public static final byte[] CF_SCORE = bytes("cf_score");

    /** driver_profile: slowly-changing reference data about the driver. */
    public static final byte[] CF_PROFILE = bytes("cf_profile");

    /** Windowed metrics are operational, not archival — the Parquet archive is the record. */
    private static final int TIME_SERIES_TTL_SECONDS = (int) TimeUnit.DAYS.toSeconds(7);

    private HBaseSchema() {
    }

    /** An HBase client configuration pointed at the given ZooKeeper ensemble. */
    public static Configuration configuration(String zkQuorum, String zkPort) {
        Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", zkQuorum);
        conf.set("hbase.zookeeper.property.clientPort", zkPort);
        // Fewer retries than the default of 15, but not so few that ordinary HBase
        // housekeeping looks like an outage: a region being split, moved or
        // reassigned makes writes to it fail for tens of seconds, and that is
        // normal operation rather than a fault.
        //
        // The retry count is not what bounds the wait — HBase backs off on a rising
        // schedule, so eight retries is closer to ninety seconds than to eight
        // pauses. The operation timeout is the real bound, and it is what keeps a
        // dead cluster from stalling a micro-batch indefinitely: each attempt gives
        // up at thirty seconds, Spark retries the task, and the batch fails in
        // about two minutes with a usable error rather than hanging.
        conf.set("hbase.client.retries.number", "6");
        conf.set("hbase.client.pause", "500");
        conf.set("hbase.rpc.timeout", "20000");
        conf.set("hbase.client.operation.timeout", "30000");
        conf.set("zookeeper.recovery.retry", "2");
        return conf;
    }

    /**
     * Create the three tables if they are absent, waiting for HBase to come up.
     *
     * <p>Run once on the driver at start-up. It is idempotent, so a restarted job
     * finds its tables and carries on rather than failing — which also means the
     * stack needs no separate schema-migration step to be clone-and-run.
     *
     * @param attempts how many times to retry a connection before giving up
     */
    public static void ensureTables(Configuration conf, int attempts, long retryDelayMillis) {
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try (Connection connection = ConnectionFactory.createConnection(conf);
                 Admin admin = connection.getAdmin()) {
                createIfAbsent(admin, timeSeriesTable(SEGMENT_METRICS, CF_TRAFFIC, CF_RISK));
                createIfAbsent(admin, timeSeriesTable(VEHICLE_RISK, CF_BEHAVIOR, CF_SCORE));
                createIfAbsent(admin, referenceTable(DRIVER_PROFILE, CF_PROFILE));
                // A reachable master is not a writable cluster. After a restart the
                // master accepts connections and answers schema questions long
                // before the region server has been assigned the regions, and a
                // write in that gap fails with NotServingRegionException. Waiting
                // for availability here means the job does not depend on its
                // orchestrator's health check being deep enough to know the
                // difference.
                awaitAvailable(admin, attempts, retryDelayMillis,
                        SEGMENT_METRICS, VEHICLE_RISK, DRIVER_PROFILE);
                return;
            } catch (IOException e) {
                last = e;
                log.warn("HBase not ready (attempt {}/{}): {}", attempt, attempts, e.getMessage());
                sleep(retryDelayMillis);
            }
        }
        throw new IllegalStateException(
                "Could not reach HBase at " + conf.get("hbase.zookeeper.quorum")
                        + ":" + conf.get("hbase.zookeeper.property.clientPort")
                        + " after " + attempts + " attempts. If the job is running outside the"
                        + " compose network, remember HBase advertises a hostname, so the host"
                        + " needs '127.0.0.1  hbase' in its hosts file.", last);
    }

    /**
     * Block until every region of each table is assigned and serving.
     *
     * <p>{@code isTableAvailable} is the question that matters — not whether the
     * table exists, but whether writes to it will be accepted.
     */
    private static void awaitAvailable(Admin admin, int attempts, long retryDelayMillis,
                                       TableName... tables) throws IOException {
        for (TableName table : tables) {
            boolean ready = false;
            for (int attempt = 1; attempt <= attempts && !ready; attempt++) {
                try {
                    ready = admin.isTableAvailable(table);
                } catch (IOException e) {
                    // Thrown while hbase:meta is itself recovering, which is the
                    // very situation being waited out.
                    log.debug("{} not available yet: {}", table.getNameAsString(), e.getMessage());
                }
                if (!ready) {
                    log.info("Waiting for HBase table {} to come online ({}/{})",
                            table.getNameAsString(), attempt, attempts);
                    sleep(retryDelayMillis);
                }
            }
            if (!ready) {
                throw new IllegalStateException("HBase table " + table.getNameAsString()
                        + " did not come online; the cluster is up but not serving its regions");
            }
        }
        log.info("All HBase tables online");
    }

    private static TableDescriptorBuilder timeSeriesTable(TableName name, byte[]... families) {
        TableDescriptorBuilder table = TableDescriptorBuilder.newBuilder(name);
        for (byte[] family : families) {
            table.setColumnFamily(ColumnFamilyDescriptorBuilder.newBuilder(family)
                    .setMaxVersions(1)
                    .setTimeToLive(TIME_SERIES_TTL_SECONDS)
                    .build());
        }
        return table;
    }

    private static TableDescriptorBuilder referenceTable(TableName name, byte[]... families) {
        TableDescriptorBuilder table = TableDescriptorBuilder.newBuilder(name);
        for (byte[] family : families) {
            // No TTL: reference data has to outlive the metrics that point at it,
            // or a quiet vehicle's rows would lose their driver.
            table.setColumnFamily(ColumnFamilyDescriptorBuilder.newBuilder(family)
                    .setMaxVersions(1)
                    .build());
        }
        return table;
    }

    private static void createIfAbsent(Admin admin, TableDescriptorBuilder table) throws IOException {
        TableName name = table.build().getTableName();
        if (admin.tableExists(name)) {
            log.info("HBase table {} already present", name.getNameAsString());
            return;
        }
        admin.createTable(table.build());
        log.info("Created HBase table {}", name.getNameAsString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for HBase", e);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
