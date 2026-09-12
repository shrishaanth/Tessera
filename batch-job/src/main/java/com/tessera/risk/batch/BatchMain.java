package com.tessera.risk.batch;

import java.util.List;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.spark.sql.functions.avg;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.count;

/**
 * Turns the Parquet archive into a labelled training set (FR-3.1, FR-3.2).
 *
 * <p>Reads with the DataFrame API because Parquet is columnar and the reader can
 * skip the columns and partitions nothing needs, drops to the RDD API for the
 * reshaping and joining that is this job's actual work, then returns to a DataFrame
 * to write the result. Using one API throughout would mean giving up either the
 * column pruning or the typed transformations for no benefit.
 *
 * <p>The class balance is reported at the end, and deliberately so. The majority
 * class here is well over 80%, which makes accuracy a meaningless score — a model
 * that predicts "no incident" every time already achieves it. Printing the baseline
 * next to the row count means the next stage cannot quietly claim a high accuracy
 * as a success.
 */
public final class BatchMain {

    private static final Logger log = LoggerFactory.getLogger(BatchMain.class);

    private BatchMain() {
    }

    public static void main(String[] args) {
        BatchConfig config = BatchConfig.fromEnvironment();
        log.info("Tessera Risk batch feature job");
        log.info("  archive   {}", config.archivePath());
        log.info("  features  {}", config.featuresPath());
        log.info("  window    {}s tumbling, at least {} readings",
                config.windowSeconds(), config.minReadings());

        SparkSession spark = SparkSession.builder()
                .appName("tessera-risk-batch-features")
                .master(envString("SPARK_MASTER_URL", "local[*,4]"))
                .config("spark.sql.session.timeZone", "UTC")
                .config("spark.sql.shuffle.partitions", "8")
                .getOrCreate();

        Dataset<Row> archive = spark.read().parquet(config.archivePath());
        // ACTION — forces the read, and the number is worth logging: if the archive
        // is empty or a path is wrong, this says so before any work is done.
        long readings = archive.count();
        log.info("archive holds {} readings across {} partition(s)",
                String.format("%,d", readings), archive.rdd().getNumPartitions());

        JavaRDD<Row> labelled = FeatureExtraction.labelledRows(
                archive.javaRDD(), config.windowSeconds(), config.minReadings());

        Dataset<Row> features = spark
                .createDataFrame(labelled, FeatureSchema.TRAINING_ROW)
                // Written once and then summarised twice; without this the whole
                // RDD pipeline, shuffle and join included, would run three times.
                .persist(StorageLevel.MEMORY_AND_DISK());

        features.write()
                .mode(SaveMode.Overwrite)
                .parquet(config.featuresPath());

        report(features);
        features.unpersist();
        spark.stop();
    }

    /**
     * Print what the training set actually contains.
     *
     * <p>Not decoration. The positive rate decides which metrics mean anything
     * downstream, and the per-tier rates decide whether the task is worth posing at
     * all: if any tier's positive rate crossed a half, a classifier could match the
     * majority baseline knowing nothing but who was driving.
     */
    private static void report(Dataset<Row> features) {
        // ACTION — collect, on aggregates only, so what returns to the driver is a
        // handful of rows rather than the training set.
        long rows = features.count();
        long positives = features.filter(col(FeatureSchema.LABEL).equalTo(1.0)).count();

        if (rows == 0) {
            log.warn("No labelled rows were produced. Is the archive long enough to "
                    + "contain two consecutive windows for a vehicle?");
            return;
        }

        double positiveRate = 100.0 * positives / rows;
        double baseline = 100.0 * Math.max(positives, rows - positives) / rows;
        log.info("training set: {} rows, {} positive ({}%)",
                String.format("%,d", rows), String.format("%,d", positives),
                String.format("%.1f", positiveRate));
        log.info("majority-class baseline accuracy: {}% — any model must beat this",
                String.format("%.1f", baseline));

        List<Row> byTier = features
                .groupBy(col(FeatureSchema.RISK_TIER_ORDINAL))
                .agg(count("*").as("rows"), avg(col(FeatureSchema.LABEL)).as("positiveRate"))
                .orderBy(col(FeatureSchema.RISK_TIER_ORDINAL))
                .collectAsList();
        for (Row row : byTier) {
            log.info("  tier ordinal {}: {} rows, {}% positive",
                    (int) row.getDouble(0), String.format("%,d", row.getLong(1)),
                    String.format("%.1f", 100.0 * row.getDouble(2)));
        }
    }

    private static String envString(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
