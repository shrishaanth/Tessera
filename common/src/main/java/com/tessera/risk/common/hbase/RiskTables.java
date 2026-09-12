package com.tessera.risk.common.hbase;

/**
 * Names of the HBase tables, column families and qualifiers.
 *
 * <h2>Why these are plain strings in common</h2>
 * Two services address the same rows from opposite ends. The streaming job writes
 * them; the reporting API reads them. If each declared its own name for
 * {@code cf_score:probability}, a rename on the write side would not break the read
 * side — the reader would simply find nothing there and serve nulls, and the
 * dashboard would show a fleet with no predictions rather than an error.
 *
 * <p>Deliberately free of any HBase dependency. They are identifiers, not client
 * objects, so the writer turns them into {@code TableName} and {@code byte[]} and
 * the reader does the same, without either needing the other's classpath. That is
 * what lets the reporting API share this without inheriting Spark.
 *
 * @see RowKeys for the row-key format these tables are addressed by
 */
public final class RiskTables {

    /** Windowed traffic and risk per road segment. Row key: {@code segmentId#reverseTs}. */
    public static final String SEGMENT_METRICS = "segment_metrics";
    /** Windowed behaviour and scores per vehicle. Row key: {@code vehicleId#reverseTs}. */
    public static final String VEHICLE_RISK = "vehicle_risk";
    /** Slowly-changing reference data. Row key: {@code vehicleId}. */
    public static final String DRIVER_PROFILE = "driver_profile";

    /** segment_metrics: how traffic is flowing over a stretch of road. */
    public static final String CF_TRAFFIC = "cf_traffic";
    /** segment_metrics: unsafe behaviour observed on that stretch. */
    public static final String CF_RISK = "cf_risk";
    /** vehicle_risk: what the vehicle did. */
    public static final String CF_BEHAVIOR = "cf_behavior";
    /** vehicle_risk: what the rules and the model make of it. */
    public static final String CF_SCORE = "cf_score";
    /** driver_profile: who was driving. */
    public static final String CF_PROFILE = "cf_profile";

    // Qualifiers. A qualifier carries the same meaning wherever it appears, so one
    // written to more than one table is declared once: the hardBrakeCount in cf_risk
    // and the one in cf_behavior count the same thing about different subjects.

    // Counts, shared by segment_metrics/cf_risk and vehicle_risk/cf_behavior.
    public static final String HARD_BRAKE_COUNT = "hardBrakeCount";
    public static final String SPEED_VIOLATION_COUNT = "speedViolationCount";
    public static final String INCIDENT_COUNT = "incidentCount";
    public static final String READING_COUNT = "readingCount";

    // Speed, shared by segment_metrics/cf_traffic and vehicle_risk/cf_behavior.
    public static final String AVG_SPEED_KPH = "avgSpeedKph";
    public static final String MAX_SPEED_KPH = "maxSpeedKph";
    public static final String SPEED_LIMIT_KPH = "speedLimitKph";
    public static final String AVG_OVER_LIMIT_RATIO = "avgOverLimitRatio";

    /** Window end, stored so a reader knows the span without re-deriving it from the key. */
    public static final String WINDOW_END = "windowEnd";

    // segment_metrics only.
    public static final String VEHICLE_COUNT = "vehicleCount";
    public static final String COMPLIANCE_RATIO = "complianceRatio";
    public static final String RISK_INDEX = "riskIndex";

    // vehicle_risk / cf_behavior.
    public static final String MAX_SEVERITY = "maxSeverity";
    public static final String DRIVER_ID = "driverId";
    public static final String RISK_TIER = "riskTier";
    public static final String MOVING_RATIO = "movingRatio";
    /**
     * Last known position within the window, and when it was observed.
     *
     * <p>A windowed aggregate has no natural position, so these are the reading with
     * the greatest timestamp in the window rather than an average — averaging a
     * vehicle's coordinates over five minutes would place it somewhere it never was,
     * quite possibly off the road entirely.
     */
    public static final String LAT = "lat";
    public static final String LON = "lon";
    public static final String HEADING_DEG = "headingDeg";
    public static final String LAST_READING_TS = "lastReadingTs";

    // vehicle_risk / cf_score.
    public static final String RISK_SCORE = "riskScore";
    /** The model's class prediction, 0 or 1. Absent until a model has been trained. */
    public static final String PREDICTED_LABEL = "predictedLabel";
    /** The model's confidence in the positive class. Absent until a model exists. */
    public static final String PROBABILITY = "probability";

    // driver_profile / cf_profile.
    public static final String DRIVER_NAME = "driverName";
    public static final String LAST_SEEN_TS = "lastSeenTs";

    private RiskTables() {
    }
}
