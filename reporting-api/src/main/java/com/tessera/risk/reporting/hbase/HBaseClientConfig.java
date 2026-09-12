package com.tessera.risk.reporting.hbase;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;

import com.tessera.risk.reporting.ReportingProperties;

/**
 * The HBase connection, as a singleton bean.
 *
 * <h2>One connection for the application's life</h2>
 * An HBase {@link Connection} is heavyweight — it holds a ZooKeeper session and a
 * cache of region locations — and is thread-safe, so the right granularity is one
 * per process shared by every request. Opening one per request would pay the setup
 * cost and throw away the region cache each time, turning every lookup into several
 * round trips.
 *
 * <h2>Why the timeouts differ from the streaming job's</h2>
 * The streaming job tolerates about thirty seconds of HBase unavailability, because
 * a micro-batch can afford to wait out a region reassignment and Spark will retry
 * the task. An HTTP request cannot: a dashboard poll that blocks for thirty seconds
 * has already failed as far as the user is concerned. So this client gives up in a
 * few seconds and lets the endpoint return an error the client can retry. The
 * settings are deliberately not shared with the writer — they encode different
 * requirements, not the same one twice.
 */
@org.springframework.context.annotation.Configuration
public class HBaseClientConfig {

    private static final Logger log = LoggerFactory.getLogger(HBaseClientConfig.class);

    /**
     * Bean is destroyed on shutdown, which closes the ZooKeeper session rather than
     * leaving it to expire on the server.
     */
    @Bean(destroyMethod = "close")
    public Connection hbaseConnection(ReportingProperties properties) throws IOException {
        Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", properties.getHbaseZkQuorum());
        conf.set("hbase.zookeeper.property.clientPort", properties.getHbaseZkPort());
        conf.set("hbase.client.retries.number", "3");
        conf.set("hbase.client.pause", "200");
        conf.set("hbase.rpc.timeout", "4000");
        conf.set("hbase.client.operation.timeout", "6000");
        conf.set("hbase.client.scanner.timeout.period", "6000");
        conf.set("zookeeper.recovery.retry", "1");

        log.info("Connecting to HBase at {}:{}",
                properties.getHbaseZkQuorum(), properties.getHbaseZkPort());
        return ConnectionFactory.createConnection(conf);
    }
}
