package com.tessera.risk.streaming;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;

import com.tessera.risk.common.model.EventType;

import static com.tessera.risk.common.spark.TelemetrySchema.EVENT_TIME;
import static com.tessera.risk.common.spark.TelemetrySchema.TELEMETRY;
import static org.apache.spark.sql.functions.approx_count_distinct;
import static org.apache.spark.sql.functions.avg;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.count;
import static org.apache.spark.sql.functions.expr;
import static org.apache.spark.sql.functions.from_json;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.max;
import static org.apache.spark.sql.functions.sum;
import static org.apache.spark.sql.functions.when;
import static org.apache.spark.sql.functions.window;

/**
 * The windowed aggregations at the centre of the streaming job (FR-2.2, FR-2.3).
 *
 * <p>Every method here takes a {@link Dataset} and returns a {@link Dataset}, with
 * no reference to Kafka, HBase, or a streaming query. That is what makes the logic
 * testable: the same transformations run against a small static DataFrame in a
 * unit test and against an unbounded stream in production, and Spark guarantees
 * they mean the same thing in both cases. Testing windowed aggregation by
 * standing up a broker is the alternative, and a far worse one.
 */
public final class RiskWindows {

    // Shared aggregate column names. Named constants rather than string literals
    // because the HBase writer, the alert rules and the tests all index into these
    // rows by name, and a typo in any one of them would otherwise only surface at
    // run time.
    public static final String WINDOW_START = "windowStart";
    public static final String WINDOW_END = "windowEnd";
    public static final String READING_COUNT = "readingCount";
    public static final String HARD_BRAKE_COUNT = "hardBrakeCount";
    public static final String SPEED_VIOLATION_COUNT = "speedViolationCount";
    public static final String INCIDENT_COUNT = "incidentCount";
    public static final String AVG_SPEED_KPH = "avgSpeedKph";

    // segment_metrics
    public static final String SEGMENT_ID = "segmentId";
    public static final String VEHICLE_COUNT = "vehicleCount";
    public static final String SPEED_LIMIT_KPH = "speedLimitKph";
    public static final String COMPLIANCE_RATIO = "complianceRatio";
    public static final String RISK_INDEX = "riskIndex";

    // vehicle_risk
    public static final String VEHICLE_ID = "vehicleId";
    public static final String DRIVER_ID = "driverId";
    public static final String RISK_TIER = "riskTier";
    public static final String MAX_SPEED_KPH = "maxSpeedKph";
    public static final String AVG_OVER_LIMIT_RATIO = "avgOverLimitRatio";
    public static final String MAX_SEVERITY = "maxSeverity";
    public static final String RISK_SCORE = "riskScore";

    private static final String COMPLIANT_COUNT = "compliantCount";
    private static final String LIMITED_COUNT = "limitedCount";

    /** Source column for the speed aggregates: the raw reading, before windowing. */
    private static final String SPEED_KPH = "speedKph";

    private RiskWindows() {
    }

    /**
     * Decode the Kafka {@code value} bytes into typed telemetry columns and attach
     * an event-time column.
     *
     * <p>A payload Spark cannot parse yields a struct of nulls rather than an
     * exception, so the null check on {@code vehicleId} is what keeps one
     * malformed message from killing the query. Dropping the record is the right
     * response here: telemetry is a continuous sample of a physical process, and a
     * reading that cannot be decoded carries no recoverable information.
     *
     * <p>Event time comes from the producer's {@code ts}, never from arrival time.
     * That is the whole point of event-time processing — a record delayed by a
     * broker hiccup still lands in the window where it physically belongs.
     */
    public static Dataset<Row> parse(Dataset<Row> kafkaFrame) {
        return kafkaFrame
                .select(from_json(col("value").cast("string"), TELEMETRY).as("event"))
                .select("event.*")
                .filter(col(VEHICLE_ID).isNotNull().and(col("ts").isNotNull()))
                .withColumn(EVENT_TIME, expr("timestamp_millis(ts)"));
    }

    /**
     * Per-road-segment traffic and risk, in sliding event-time windows (FR-2.2).
     *
     * <p>The watermark (FR-2.4) is what bounds state: without it Spark would have
     * to keep every window open forever in case a late record arrived for it, and
     * memory would grow without limit. With it, a window is finalised once the
     * watermark passes its end, and its state is released.
     */
    public static Dataset<Row> segmentWindows(Dataset<Row> parsed, StreamingConfig config) {
        return parsed
                .withWatermark(EVENT_TIME, config.watermarkDelay())
                .groupBy(
                        window(col(EVENT_TIME), config.windowDuration(), config.slideDuration()),
                        col(SEGMENT_ID))
                .agg(
                        avg(SPEED_KPH).as(AVG_SPEED_KPH),
                        // approx_count_distinct, not countDistinct: an exact distinct
                        // count is unsupported in a streaming aggregation because it
                        // would mean retaining every identifier ever seen in the
                        // window. HyperLogLog holds that in fixed space, and "how many
                        // vehicles were on this road" tolerates approximation.
                        approx_count_distinct(col(VEHICLE_ID)).as(VEHICLE_COUNT),
                        // The posted limit is a property of the segment, not a
                        // measurement, so max() here is just "the value" — averaging
                        // it would imply it varies within the window.
                        max(SPEED_LIMIT_KPH).as(SPEED_LIMIT_KPH),
                        count(lit(1)).as(READING_COUNT),
                        sum(hasKnownLimit()).as(LIMITED_COUNT),
                        sum(isCompliant()).as(COMPLIANT_COUNT),
                        sum(isEvent(EventType.HARD_BRAKE)).as(HARD_BRAKE_COUNT),
                        sum(isEvent(EventType.SPEED_VIOLATION)).as(SPEED_VIOLATION_COUNT),
                        sum(isEvent(EventType.INCIDENT)).as(INCIDENT_COUNT))
                .withColumn(COMPLIANCE_RATIO,
                        when(col(LIMITED_COUNT).gt(0),
                                col(COMPLIANT_COUNT).divide(col(LIMITED_COUNT)))
                                .otherwise(lit(1.0)))
                .withColumn(RISK_INDEX, RiskScoring.segmentRiskIndexColumn())
                .withColumn(WINDOW_START, expr("unix_millis(window.start)"))
                .withColumn(WINDOW_END, expr("unix_millis(window.end)"))
                .drop("window", COMPLIANT_COUNT, LIMITED_COUNT);
    }

    /**
     * Per-vehicle behaviour and composite risk, over the same windows (FR-2.3).
     *
     * <p>Driver and risk tier are grouping keys rather than aggregates. They are
     * functionally dependent on the vehicle, so this produces exactly the same
     * rows as grouping on the vehicle alone, but it carries both values through
     * without a {@code first()} — which in a streaming aggregation would mean
     * "whichever record Spark happened to see first", an answer that is correct
     * here only by accident.
     */
    public static Dataset<Row> vehicleWindows(Dataset<Row> parsed, StreamingConfig config) {
        return parsed
                .withWatermark(EVENT_TIME, config.watermarkDelay())
                .groupBy(
                        window(col(EVENT_TIME), config.windowDuration(), config.slideDuration()),
                        col(VEHICLE_ID), col(DRIVER_ID), col(RISK_TIER))
                .agg(
                        count(lit(1)).as(READING_COUNT),
                        sum(isEvent(EventType.HARD_BRAKE)).as(HARD_BRAKE_COUNT),
                        sum(isEvent(EventType.SPEED_VIOLATION)).as(SPEED_VIOLATION_COUNT),
                        sum(isEvent(EventType.INCIDENT)).as(INCIDENT_COUNT),
                        avg(SPEED_KPH).as(AVG_SPEED_KPH),
                        max(SPEED_KPH).as(MAX_SPEED_KPH),
                        // avg() skips nulls, so readings on a segment with no known
                        // limit are excluded from this ratio rather than counted as
                        // zero, which would drag the average down and understate how
                        // fast the vehicle was actually travelling relative to the law.
                        avg(overLimitRatio()).as(AVG_OVER_LIMIT_RATIO),
                        max("severity").as(MAX_SEVERITY))
                .withColumn(RISK_SCORE, RiskScoring.vehicleRiskScoreColumn())
                .withColumn(WINDOW_START, expr("unix_millis(window.start)"))
                .withColumn(WINDOW_END, expr("unix_millis(window.end)"))
                .drop("window");
    }

    private static Column isEvent(EventType type) {
        return when(col("eventType").equalTo(lit(type.name())), lit(1L)).otherwise(lit(0L));
    }

    private static Column hasKnownLimit() {
        return when(col(SPEED_LIMIT_KPH).gt(0), lit(1L)).otherwise(lit(0L));
    }

    private static Column isCompliant() {
        return when(col(SPEED_LIMIT_KPH).gt(0).and(col(SPEED_KPH).leq(col(SPEED_LIMIT_KPH))),
                lit(1L)).otherwise(lit(0L));
    }

    private static Column overLimitRatio() {
        return when(col(SPEED_LIMIT_KPH).gt(0),
                col(SPEED_KPH).divide(col(SPEED_LIMIT_KPH)));
    }
}
