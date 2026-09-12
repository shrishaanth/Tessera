package com.tessera.risk.batch;

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

/**
 * The shape of one labelled training row.
 *
 * <p>Shared between the batch job that writes it and the training stage that reads
 * it, so the feature order they agree on is written once. Column order matters:
 * the trainer assembles a feature vector from {@link #FEATURE_COLUMNS} in this
 * order, and a model persisted against one ordering scores nonsense against
 * another without ever failing.
 */
public final class FeatureSchema {

    public static final String VEHICLE_ID = "vehicleId";
    public static final String DRIVER_ID = "driverId";
    public static final String WINDOW_START = "windowStart";

    // Features. Rates rather than counts, so windows of differing length stay
    // comparable — the same reasoning as the streaming risk score.
    public static final String HARD_BRAKE_RATE = "hardBrakeRate";
    public static final String SPEED_VIOLATION_RATE = "speedViolationRate";
    public static final String INCIDENT_RATE = "incidentRate";
    public static final String AVG_SPEED_KPH = "avgSpeedKph";
    public static final String MAX_SPEED_KPH = "maxSpeedKph";
    public static final String AVG_OVER_LIMIT_RATIO = "avgOverLimitRatio";
    public static final String MAX_SEVERITY = "maxSeverity";
    public static final String MOVING_RATIO = "movingRatio";
    public static final String HOUR_OF_DAY = "hourOfDay";
    /**
     * The driver's standing profile as an ordinal, 0 to 2.
     *
     * <p>An input feature and never the label (FR-3.3). A fleet operator genuinely
     * knows a driver's history, so using it is realistic; using it as the label
     * would reduce the task to a lookup on vehicle identity.
     */
    public static final String RISK_TIER_ORDINAL = "riskTierOrdinal";
    /** Readings behind the row, kept for diagnostics and for filtering thin windows. */
    public static final String READING_COUNT = "readingCount";

    /** 1 when the vehicle has an incident in the window after this one. */
    public static final String LABEL = "label";

    /** Feature columns, in the order the trainer must assemble them. */
    public static final String[] FEATURE_COLUMNS = {
            HARD_BRAKE_RATE,
            SPEED_VIOLATION_RATE,
            INCIDENT_RATE,
            AVG_SPEED_KPH,
            MAX_SPEED_KPH,
            AVG_OVER_LIMIT_RATIO,
            MAX_SEVERITY,
            MOVING_RATIO,
            HOUR_OF_DAY,
            RISK_TIER_ORDINAL,
    };

    public static final StructType TRAINING_ROW = new StructType()
            .add(VEHICLE_ID, DataTypes.StringType)
            .add(DRIVER_ID, DataTypes.StringType)
            .add(WINDOW_START, DataTypes.LongType)
            .add(HARD_BRAKE_RATE, DataTypes.DoubleType)
            .add(SPEED_VIOLATION_RATE, DataTypes.DoubleType)
            .add(INCIDENT_RATE, DataTypes.DoubleType)
            .add(AVG_SPEED_KPH, DataTypes.DoubleType)
            .add(MAX_SPEED_KPH, DataTypes.DoubleType)
            .add(AVG_OVER_LIMIT_RATIO, DataTypes.DoubleType)
            .add(MAX_SEVERITY, DataTypes.DoubleType)
            .add(MOVING_RATIO, DataTypes.DoubleType)
            .add(HOUR_OF_DAY, DataTypes.DoubleType)
            .add(RISK_TIER_ORDINAL, DataTypes.DoubleType)
            .add(READING_COUNT, DataTypes.LongType)
            .add(LABEL, DataTypes.DoubleType);

    private FeatureSchema() {
    }
}
