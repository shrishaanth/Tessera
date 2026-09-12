package com.tessera.risk.streaming;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.spark.api.java.function.VoidFunction2;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.OutputMode;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.Trigger;
import org.apache.spark.storage.StorageLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tessera.risk.common.model.RiskAlert;
import com.tessera.risk.streaming.alert.AlertPublisher;
import com.tessera.risk.streaming.alert.AlertRules;
import com.tessera.risk.streaming.hbase.HBaseSchema;
import com.tessera.risk.streaming.hbase.HBaseWriter;
import com.tessera.risk.streaming.ml.RiskModel;

/**
 * Entry point for the Spark Structured Streaming job (FR-2).
 *
 * <p>Reads {@code vehicle.telemetry}, aggregates it into sliding event-time
 * windows per road segment and per vehicle, writes both into HBase, and raises
 * alerts for vehicles that cross a risk threshold.
 *
 * <h2>Two queries, not one</h2>
 * Structured Streaming allows a single aggregation per query, so the per-segment
 * and per-vehicle rollups run as two independent queries sharing one parsed
 * source definition. Each therefore reads the topic for itself. That is a real
 * cost — the telemetry is consumed twice — and it is the right trade here: the
 * alternative is to hand-roll a combined aggregation whose two halves have
 * different grouping keys, which would mean flattening both into a common schema
 * and taking over the state management Spark already does correctly.
 *
 * <h2>Why update mode</h2>
 * Append mode would emit a window only once its watermark had passed, so a
 * five-minute window would first reach the dashboard seven minutes after the
 * driving it describes. Update mode emits each window as it fills, and because an
 * HBase {@code Put} is an upsert keyed by entity and window start, the row simply
 * refines until the window closes. Live risk that is seven minutes old is not live
 * risk.
 */
public final class StreamingMain {

    private static final Logger log = LoggerFactory.getLogger(StreamingMain.class);

    /** Attempts to reach HBase before giving up, at five seconds apart. */
    private static final int HBASE_ATTEMPTS = 24;
    private static final long HBASE_RETRY_MILLIS = 5_000L;

    private StreamingMain() {
    }

    public static void main(String[] args) throws Exception {
        StreamingConfig config = StreamingConfig.fromEnvironment();
        log.info("Tessera Risk streaming job");
        log.info("  kafka       {} topic={}", config.bootstrapServers(), config.telemetryTopic());
        log.info("  hbase       {}:{}", config.hbaseZkQuorum(), config.hbaseZkPort());
        log.info("  windows     {} sliding every {}, watermark {}",
                config.windowDuration(), config.slideDuration(), config.watermarkDelay());
        log.info("  checkpoint  {}", config.checkpointDir());
        log.info("  model       {}", config.modelPath());
        log.info("All telemetry consumed by this job is simulated; see the project README.");

        HBaseSchema.ensureTables(
                HBaseSchema.configuration(config.hbaseZkQuorum(), config.hbaseZkPort()),
                HBASE_ATTEMPTS, HBASE_RETRY_MILLIS);

        SparkSession spark = session(config);
        // Loaded once on the driver, not per batch: deserialising a pipeline is
        // expensive and the model does not change while the query runs.
        Optional<RiskModel> model = RiskModel.load(spark, config.modelPath());
        HBaseWriter writer = new HBaseWriter(config.hbaseZkQuorum(), config.hbaseZkPort());
        AlertPublisher publisher = new AlertPublisher(config);
        Runtime.getRuntime().addShutdownHook(new Thread(publisher::close, "alert-publisher-close"));

        Dataset<Row> parsed = RiskWindows.parse(kafkaSource(spark, config));

        // Each batch is consumed by more than one action — the HBase write, the
        // row count for the log, and for vehicles also the profile upsert and the
        // alert rules. A micro-batch DataFrame is lazy like any other, so without
        // caching Spark would replay the whole windowed aggregation once per
        // action instead of once per batch.
        StreamingQuery segments = start(
                RiskWindows.segmentWindows(parsed, config), "segment-metrics", config,
                (batch, batchId) -> cached(batch, () -> {
                    writer.writeSegmentMetrics(batch);
                    log.info("batch {} | segment_metrics rows={}", batchId, batch.count());
                }));

        StreamingQuery vehicles = start(
                RiskWindows.vehicleWindows(parsed, config), "vehicle-risk", config,
                (batch, batchId) -> {
                    // Scored before anything reads the batch, so the HBase write and
                    // the alert rules both see the prediction columns. Without a
                    // trained model this is the identity and the columns are absent.
                    Dataset<Row> scored = model.isPresent() ? model.get().score(batch) : batch;
                    cached(scored, () -> {
                        writer.writeVehicleRisk(scored);
                        writer.writeDriverProfiles(scored);
                        long now = System.currentTimeMillis();
                        List<RiskAlert> raised = publisher.publish(
                                AlertRules.evaluate(scored, config, now), now);
                        log.info("batch {} | vehicle_risk rows={} alerts={}",
                                batchId, scored.count(), raised.size());
                        for (RiskAlert alert : raised) {
                            log.warn("ALERT {} ({}) — {}",
                                    alert.vehicleId(), alert.riskTier(), alert.reason());
                        }
                    });
                });

        log.info("Streaming queries started: {}, {}", segments.name(), vehicles.name());
        spark.streams().awaitAnyTermination();
    }

    /**
     * Run {@code work} with the batch cached, releasing it however the work ends.
     *
     * <p>The unpersist has to happen even on failure: Spark retries a failed batch,
     * and a cached DataFrame left behind on every failure would fill the block
     * manager until the executor started evicting the data it still needed.
     */
    private static void cached(Dataset<Row> batch, Runnable work) {
        batch.persist(StorageLevel.MEMORY_AND_DISK());
        try {
            work.run();
        } finally {
            batch.unpersist();
        }
    }

    private static SparkSession session(StreamingConfig config) {
        return SparkSession.builder()
                .appName("tessera-risk-streaming")
                .master(config.sparkMaster())
                // Event time is meaningless unless every component agrees on a zone,
                // and the producer stamps epoch millis. UTC keeps window boundaries
                // identical regardless of where the job runs.
                .config("spark.sql.session.timeZone", "UTC")
                // The default of 200 shuffle partitions is calibrated for a cluster
                // and a large dataset. At this volume it produces 200 near-empty
                // tasks per micro-batch, where scheduling overhead alone would exceed
                // the batch interval.
                .config("spark.sql.shuffle.partitions", "8")
                .getOrCreate();
    }

    private static Dataset<Row> kafkaSource(SparkSession spark, StreamingConfig config) {
        return spark.readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", config.bootstrapServers())
                .option("subscribe", config.telemetryTopic())
                .option("startingOffsets", config.startingOffsets())
                // Bounds how far a single batch can reach when the job restarts
                // behind the topic. Without it the first batch after an outage tries
                // to process the entire backlog at once, and its windows would span
                // the whole gap.
                .option("maxOffsetsPerTrigger", config.maxOffsetsPerTrigger())
                // A topic deleted and recreated between runs leaves the checkpoint
                // pointing at offsets that no longer exist. Failing loudly is right
                // in production; in a stack that is torn down and rebuilt routinely
                // it just blocks the restart.
                .option("failOnDataLoss", "false")
                .load();
    }

    private static StreamingQuery start(Dataset<Row> aggregated, String name,
                                        StreamingConfig config,
                                        VoidFunction2<Dataset<Row>, Long> sink) throws Exception {
        return aggregated.writeStream()
                .queryName(name)
                .outputMode(OutputMode.Update())
                .trigger(Trigger.ProcessingTime(config.triggerSeconds(), TimeUnit.SECONDS))
                // Each query keeps its own checkpoint: they consume the topic
                // independently, so sharing one would have them overwrite each
                // other's offsets and state.
                .option("checkpointLocation", config.checkpointDir() + "/" + name)
                .foreachBatch(sink)
                .start();
    }
}
