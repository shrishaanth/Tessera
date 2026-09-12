package com.tessera.risk.batch;

import java.io.Serializable;

/**
 * Running totals for one vehicle over one window, combined by {@code reduceByKey}.
 *
 * <h2>Why sums and not averages</h2>
 * Every field is either a sum, a count or a maximum — quantities that combine
 * associatively, so two partial aggregates can be merged without knowing how many
 * readings went into either. Averages cannot: merging two means requires their
 * weights, and a mean of means is simply wrong. Averages are therefore derived at
 * the end, from {@code speedSum / readings}, rather than carried along.
 *
 * <p>That is also what lets Spark combine on the map side before shuffling, which
 * is the reason to prefer {@code reduceByKey} over {@code groupByKey} here: the
 * latter would move every individual reading across the network to be aggregated
 * at the reducer, where this moves one small object per partition per key.
 *
 * <p>Serializable because instances cross the shuffle boundary.
 */
public record WindowAggregate(
        long readings,
        long hardBrakes,
        long speedViolations,
        long incidents,
        double speedSum,
        double maxSpeed,
        double overLimitSum,
        long overLimitReadings,
        double maxSeverity,
        long movingReadings,
        String driverId,
        String riskTier) implements Serializable {

    /** Speed below which a vehicle counts as stationary rather than driving. */
    private static final double MOVING_KPH = 1.0;

    /** An aggregate holding exactly one reading. */
    public static WindowAggregate of(String eventType, double speedKph, double speedLimitKph,
                                     double severity, String driverId, String riskTier) {
        boolean limitKnown = speedLimitKph > 0;
        return new WindowAggregate(
                1L,
                "HARD_BRAKE".equals(eventType) ? 1L : 0L,
                "SPEED_VIOLATION".equals(eventType) ? 1L : 0L,
                "INCIDENT".equals(eventType) ? 1L : 0L,
                speedKph,
                speedKph,
                limitKnown ? speedKph / speedLimitKph : 0.0,
                limitKnown ? 1L : 0L,
                severity,
                speedKph > MOVING_KPH ? 1L : 0L,
                driverId,
                riskTier);
    }

    /** Merge two partial aggregates for the same vehicle and window. */
    public WindowAggregate merge(WindowAggregate other) {
        return new WindowAggregate(
                readings + other.readings,
                hardBrakes + other.hardBrakes,
                speedViolations + other.speedViolations,
                incidents + other.incidents,
                speedSum + other.speedSum,
                Math.max(maxSpeed, other.maxSpeed),
                overLimitSum + other.overLimitSum,
                overLimitReadings + other.overLimitReadings,
                Math.max(maxSeverity, other.maxSeverity),
                movingReadings + other.movingReadings,
                // Identical for every reading of a vehicle, so either side will do.
                driverId,
                riskTier);
    }

    public double avgSpeedKph() {
        return readings == 0 ? 0.0 : speedSum / readings;
    }

    /**
     * Mean speed as a fraction of the posted limit, over readings where a limit was
     * known. Readings without one are excluded rather than counted as zero, which
     * would understate how fast the vehicle was travelling relative to the law.
     */
    public double avgOverLimitRatio() {
        return overLimitReadings == 0 ? 0.0 : overLimitSum / overLimitReadings;
    }

    /** Fraction of the window spent in motion; distinguishes a quiet driver from a parked one. */
    public double movingRatio() {
        return readings == 0 ? 0.0 : (double) movingReadings / readings;
    }

    public double hardBrakeRate() {
        return readings == 0 ? 0.0 : (double) hardBrakes / readings;
    }

    public double speedViolationRate() {
        return readings == 0 ? 0.0 : (double) speedViolations / readings;
    }

    public double incidentRate() {
        return readings == 0 ? 0.0 : (double) incidents / readings;
    }
}
