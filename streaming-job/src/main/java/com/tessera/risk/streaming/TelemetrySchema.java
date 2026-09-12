package com.tessera.risk.streaming;

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

/**
 * The wire schema of {@code vehicle.telemetry}, declared explicitly.
 *
 * <p>Structured Streaming cannot infer a schema from an unbounded source — there
 * is no finite sample to infer from — so this is not merely a preference. It is
 * also the better engineering choice: the schema is pinned in one reviewable
 * place, and a producer that starts emitting a malformed or renamed field
 * surfaces as nulls in a known column rather than silently reshaping every
 * downstream aggregation.
 *
 * <p>Enums arrive as their {@code name()} strings. They are kept as strings here
 * rather than being mapped back to Java enums, because comparisons then happen
 * inside Spark's generated code instead of forcing a per-row deserialisation into
 * JVM objects.
 *
 * <p>Must stay in step with {@link com.tessera.risk.common.model.TelemetryEvent}.
 * {@code TelemetrySchemaTest} fails if the two drift apart.
 */
public final class TelemetrySchema {

    /** Column holding the parsed event time, derived from the epoch-millis {@code ts}. */
    public static final String EVENT_TIME = "eventTime";

    public static final StructType TELEMETRY = new StructType()
            .add("vehicleId", DataTypes.StringType)
            .add("driverId", DataTypes.StringType)
            .add("riskTier", DataTypes.StringType)
            .add("lat", DataTypes.DoubleType)
            .add("lon", DataTypes.DoubleType)
            .add("speedKph", DataTypes.DoubleType)
            .add("headingDeg", DataTypes.DoubleType)
            .add("segmentId", DataTypes.StringType)
            .add("speedLimitKph", DataTypes.DoubleType)
            .add("eventType", DataTypes.StringType)
            .add("severity", DataTypes.DoubleType)
            .add("ts", DataTypes.LongType);

    private TelemetrySchema() {
    }
}
