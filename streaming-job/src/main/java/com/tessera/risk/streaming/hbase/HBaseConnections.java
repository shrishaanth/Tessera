package com.tessera.risk.streaming.hbase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One HBase connection per JVM per cluster, shared by every task that runs there.
 *
 * <h2>Why this exists</h2>
 * An HBase {@link Connection} is heavyweight by design: creating one opens a
 * ZooKeeper session, fetches the cluster id, and begins building a cache of region
 * locations. HBase's own guidance is to create it once and share it, because the
 * region cache is the thing that makes subsequent operations single-hop.
 *
 * <p>Creating one inside {@code foreachPartition} is the obvious thing to write
 * and is wrong at a streaming cadence. With a ten-second trigger, eight shuffle
 * partitions and three tables written per batch, it means roughly two dozen
 * ZooKeeper sessions opened and discarded every ten seconds — each one paying the
 * setup cost and then throwing away the region cache it had just built.
 *
 * <p>Spark runs many tasks in one executor JVM, so caching here is exactly the
 * right granularity: one connection per executor, reused for the life of the
 * process, and the map has a single entry unless the job talks to two clusters.
 *
 * <h2>Lifecycle</h2>
 * Deliberately never closed on a per-batch basis — closing is what the cache
 * exists to avoid. A shutdown hook releases the sessions when the JVM ends, which
 * matters because an unclosed ZooKeeper session lingers on the server until its
 * timeout expires.
 */
public final class HBaseConnections {

    private static final Logger log = LoggerFactory.getLogger(HBaseConnections.class);

    private static final Map<String, Connection> CACHE = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(HBaseConnections::closeAll, "hbase-close"));
    }

    private HBaseConnections() {
    }

    /** The connection for this ZooKeeper ensemble, creating it on first use. */
    public static Connection get(String zkQuorum, String zkPort) {
        return CACHE.computeIfAbsent(zkQuorum + ":" + zkPort, key -> {
            log.info("Opening HBase connection to {}", key);
            try {
                return ConnectionFactory.createConnection(
                        HBaseSchema.configuration(zkQuorum, zkPort));
            } catch (IOException e) {
                // Unchecked so this can be used from computeIfAbsent and from Spark
                // closures. A task that cannot reach HBase should fail the batch,
                // which Spark then retries.
                throw new UncheckedIOException("Could not connect to HBase at " + key, e);
            }
        });
    }

    private static void closeAll() {
        for (Map.Entry<String, Connection> entry : CACHE.entrySet()) {
            try {
                entry.getValue().close();
            } catch (IOException e) {
                log.warn("Could not close HBase connection to {}: {}",
                        entry.getKey(), e.getMessage());
            }
        }
        CACHE.clear();
    }
}
