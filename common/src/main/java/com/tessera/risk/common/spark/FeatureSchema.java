package com.tessera.risk.common.spark;

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

/**
 * The feature contract shared by training and scoring.
 *
 * <h2>Why this is shared rather than declared twice</h2>
 * The batch job derives these features from a Parquet archive and the streaming job
 * derives them from a live Kafka stream. Two completely different code paths, and
 * they must agree exactly, because a model is a function of the feature vector it
 * was fitted to. If the streaming job computed {@code hardBrakeRate} over a
 * different denominator, or assembled the vector in a different order, the model
 * would go on returning confident probabilities that mean nothing — and nothing in
 * the pipeline would report it. Predictions do not fail loudly; they just stop
 * being about anything.
 *
 * <p>That failure mode has a name — training/serving skew — and the only reliable
 * defence is for there to be one definition. Hence this class, and hence
 * {@link FeatureVector}, which derives the features from the aggregate columns
 * below so that neither job writes the arithmetic itself.
 *
 * <h2>Two groups of columns</h2>
 * {@link #AGGREGATE_COLUMNS} are the inputs: what a windowed aggregation produces,
 * whether by Spark SQL in the streaming job or by {@code reduceByKey} in the batch
 * job. {@link #FEATURE_COLUMNS} are what the model consumes, and their order is
 * part of the contract: a vector assembled in a different order scores nonsense
 * against a persisted model without ever raising an error.
 */
public final class FeatureSchema {

    // Identity and time. Not features — carried alongside them.
    public static final String VEHICLE_ID = "vehicleId";
    public static final String DRIVER_ID = "driverId";
    public static final String WINDOW_START = "windowStart";
    public static final String RISK_TIER = "riskTier";

    // Aggregate inputs: counts and simple statistics over one window.
    public static final String READING_COUNT = "readingCount";
    public static final String HARD_BRAKE_COUNT = "hardBrakeCount";
    public static final String SPEED_VIOLATION_COUNT = "speedViolationCount";
    public static final String INCIDENT_COUNT = "incidentCount";
    public static final String AVG_SPEED_KPH = "avgSpeedKph";
    public static final String MAX_SPEED_KPH = "maxSpeedKph";
    public static final String AVG_OVER_LIMIT_RATIO = "avgOverLimitRatio";
    public static final String MAX_SEVERITY = "maxSeverity";
    public static final String MOVING_RATIO = "movingRatio";

    // Derived features.
    public static final String HARD_BRAKE_RATE = "hardBrakeRate";
    public static final String SPEED_VIOLATION_RATE = "speedViolationRate";
    public static final String INCIDENT_RATE = "incidentRate";
    public static final String HOUR_OF_DAY = "hourOfDay";
    /**
     * The driver's standing profile as an ordinal, 0 to 2.
     *
     * <p>An input feature and never the label (FR-3.3). A fleet operator genuinely
     * knows a driver's history, so using it is realistic; using it as the label
     * would reduce the task to a lookup on vehicle identity.
     */
    public static final String RISK_TIER_ORDINAL = "riskTierOrdinal";

    /** 1 when the vehicle has an incident in the window after this one. */
    public static final String LABEL = "label";

    /** Assembled vector column, and the model's prediction columns. */
    public static final String FEATURES = "features";
    public static final String PREDICTION = "prediction";
    public static final String PROBABILITY = "probability";

    /**
     * Columns a windowed aggregation must supply for {@link FeatureVector} to work.
     *
     * <p>Asserted against the streaming job's output, because a column quietly
     * missing there would surface as a null feature rather than as a failure.
     */
    public static final String[] AGGREGATE_COLUMNS = {
            READING_COUNT,
            HARD_BRAKE_COUNT,
            SPEED_VIOLATION_COUNT,
            INCIDENT_COUNT,
            AVG_SPEED_KPH,
            MAX_SPEED_KPH,
            AVG_OVER_LIMIT_RATIO,
            MAX_SEVERITY,
            MOVING_RATIO,
            WINDOW_START,
            RISK_TIER,
    };

    /** Feature columns, in the order the vector must be assembled. */
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

    /**
     * What the batch job's RDD stage emits: the aggregates plus the label.
     *
     * <p>Deliberately not the feature columns. The RDD stage's work is aggregating
     * and labelling; deriving features is {@link FeatureVector}'s job, so that the
     * streaming job can apply the identical derivation to its own aggregates.
     */
    public static final StructType AGGREGATE_ROW = new StructType()
            .add(VEHICLE_ID, DataTypes.StringType)
            .add(DRIVER_ID, DataTypes.StringType)
            .add(RISK_TIER, DataTypes.StringType)
            .add(WINDOW_START, DataTypes.LongType)
            .add(READING_COUNT, DataTypes.LongType)
            .add(HARD_BRAKE_COUNT, DataTypes.LongType)
            .add(SPEED_VIOLATION_COUNT, DataTypes.LongType)
            .add(INCIDENT_COUNT, DataTypes.LongType)
            .add(AVG_SPEED_KPH, DataTypes.DoubleType)
            .add(MAX_SPEED_KPH, DataTypes.DoubleType)
            .add(AVG_OVER_LIMIT_RATIO, DataTypes.DoubleType)
            .add(MAX_SEVERITY, DataTypes.DoubleType)
            .add(MOVING_RATIO, DataTypes.DoubleType)
            .add(LABEL, DataTypes.DoubleType);

    private FeatureSchema() {
    }
}
