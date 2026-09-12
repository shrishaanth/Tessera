package com.tessera.risk.batch;

import java.io.Serializable;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;

import com.tessera.risk.common.spark.FeatureSchema;

import scala.Tuple2;

/**
 * Builds the labelled training set from raw telemetry, using the RDD API (FR-3.1).
 *
 * <h2>Why RDDs here and DataFrames in the streaming job</h2>
 * The streaming job's work is relational — group, aggregate, window — and the
 * Catalyst optimiser does that better than hand-written code. This job's work is
 * not: it reshapes records into keyed pairs, folds them with a custom associative
 * combiner, and joins a dataset against a shifted copy of itself. Expressing that
 * through typed transformations is clearer than through SQL, and it is what the
 * requirement asks to be demonstrated.
 *
 * <h2>How the label is built</h2>
 * The label is whether the vehicle has an incident in the window <em>after</em> the
 * one the features describe (FR-3.2). Rather than sorting each vehicle's windows
 * and walking them in order, the aggregate is joined against a copy of itself whose
 * key has been shifted back one window. The join then pairs window <em>w</em>'s
 * features with window <em>w+1</em>'s incidents, and because an inner join drops
 * non-matching keys, windows with no successor disappear on their own — which is
 * exactly right, since they cannot be labelled.
 *
 * <h2>Tumbling, not sliding</h2>
 * The streaming job slides its windows by a minute, because a dashboard wants the
 * freshest view. Training windows must not overlap. Overlapping rows share
 * readings, so a row in the training split and a row in the test split could
 * describe much of the same driving — the model would be scored partly on data it
 * was fitted to, and the evaluation would flatter it for no reason.
 */
public final class FeatureExtraction {

    private FeatureExtraction() {
    }

    /** Vehicle and window index; the reduce key. A record supplies equals and hashCode. */
    public record WindowKey(String vehicleId, long window) implements Serializable { }

    /**
     * Raw telemetry rows to labelled aggregate rows matching
     * {@link FeatureSchema#AGGREGATE_ROW}.
     *
     * @param telemetry     archive rows carrying the telemetry schema
     * @param windowSeconds tumbling window width
     * @param minReadings   windows thinner than this are dropped; a partial window at
     *                      the edge of the archive would otherwise contribute rates
     *                      computed over a handful of readings
     */
    public static JavaRDD<Row> labelledRows(JavaRDD<Row> telemetry, int windowSeconds,
                                            long minReadings) {
        long windowMillis = windowSeconds * 1000L;

        // TRANSFORMATION — map each reading to a keyed one-reading aggregate.
        JavaPairRDD<WindowKey, WindowAggregate> readings = telemetry.mapToPair(row -> {
            long ts = row.getAs("ts");
            WindowKey key = new WindowKey(row.getAs("vehicleId"), ts / windowMillis);
            WindowAggregate aggregate = WindowAggregate.of(
                    row.getAs("eventType"),
                    row.getAs("speedKph"),
                    row.getAs("speedLimitKph"),
                    row.getAs("severity"),
                    row.getAs("driverId"),
                    row.getAs("riskTier"));
            return new Tuple2<>(key, aggregate);
        });

        // TRANSFORMATION — fold them together. reduceByKey, not groupByKey: the
        // combiner is associative, so Spark aggregates within each partition before
        // the shuffle and moves one object per key per partition instead of every
        // reading in the archive.
        JavaPairRDD<WindowKey, WindowAggregate> windows =
                readings.reduceByKey(WindowAggregate::merge);

        // TRANSFORMATION — drop windows too thin to describe anything.
        JavaPairRDD<WindowKey, WindowAggregate> usable =
                windows.filter(entry -> entry._2().readings() >= minReadings);

        // TRANSFORMATION — the same windows, re-keyed one window earlier, carrying
        // only what the label needs. This is the shifted copy the join pairs against.
        JavaPairRDD<WindowKey, Long> nextWindowIncidents = usable.mapToPair(entry ->
                new Tuple2<>(
                        new WindowKey(entry._1().vehicleId(), entry._1().window() - 1),
                        entry._2().incidents()));

        // TRANSFORMATION — inner join, so a window with no successor is dropped.
        JavaPairRDD<WindowKey, Tuple2<WindowAggregate, Long>> labelled =
                usable.join(nextWindowIncidents);

        // TRANSFORMATION — project to the training schema.
        return labelled.map(entry ->
                toRow(entry._1(), entry._2()._1(), entry._2()._2(), windowMillis));
    }

    /**
     * Project to {@link FeatureSchema#AGGREGATE_ROW} — the aggregates and the label,
     * not the features.
     *
     * <p>The rates, the hour and the tier ordinal are deliberately not computed
     * here. {@link com.tessera.risk.common.spark.FeatureVector} derives them, and
     * the streaming job applies that same derivation to its own aggregates. If this
     * method built the feature vector itself there would be two implementations of
     * it, and a model trained against one would be scored against the other.
     */
    private static Row toRow(WindowKey key, WindowAggregate aggregate,
                             long incidentsInNextWindow, long windowMillis) {
        return RowFactory.create(
                key.vehicleId(),
                aggregate.driverId(),
                aggregate.riskTier(),
                key.window() * windowMillis,
                aggregate.readings(),
                aggregate.hardBrakes(),
                aggregate.speedViolations(),
                aggregate.incidents(),
                aggregate.avgSpeedKph(),
                aggregate.maxSpeed(),
                aggregate.avgOverLimitRatio(),
                aggregate.maxSeverity(),
                aggregate.movingRatio(),
                // The label, and the only place the following window is consulted.
                incidentsInNextWindow > 0 ? 1.0 : 0.0);
    }
}
