package com.tessera.risk.simulation;

/**
 * Tunables for the fleet simulation and for event classification.
 *
 * <p>Every value is overridable by environment variable (NFR-3), so behaviour can
 * be changed for a demonstration without rebuilding. The defaults are chosen to
 * be physically defensible rather than merely convenient: the comfortable limits
 * are close to what a normal driver actually uses, and the thresholds that
 * classify an event sit meaningfully above them.
 *
 * @param vehicleCount        vehicles in the simulated fleet
 * @param tickMillis          wall-clock interval between telemetry readings
 * @param maxSpeedMps         hard ceiling on speed; also bounds per-tick displacement
 * @param maxAccelMps2        comfortable acceleration
 * @param comfortBrakeMps2    comfortable deceleration; the route plan is built around this
 * @param riskyBrakeMps2      deceleration a speeding driver is willing to plan around
 * @param emergencyBrakeMps2  hardest deceleration physically available
 * @param hardBrakeMps2       deceleration at or above which a reading is a HARD_BRAKE
 * @param incidentBrakeMps2   deceleration at or above which a reading is an INCIDENT
 * @param incidentComboBrakeMps2 deceleration which, while already speeding, is an INCIDENT
 * @param speedViolationRatio speed as a multiple of the posted limit that counts as a violation
 * @param violationSustainTicks consecutive ticks over the limit before a violation is raised
 * @param speedingEpisodeChance per-tick chance a RISKY driver starts speeding; scaled by tier
 * @param speedingEpisodeTicks   how long a speeding episode lasts
 * @param speedingFactorMax    the most a speeding driver will exceed the limit by
 * @param stopDwellTicksMin    shortest pause at a tour stop
 * @param stopDwellTicksMax    longest pause at a tour stop
 * @param seed                 RNG seed; fixed so runs are reproducible
 */
public record SimulatorConfig(
        int vehicleCount,
        long tickMillis,
        double maxSpeedMps,
        double maxAccelMps2,
        double comfortBrakeMps2,
        double riskyBrakeMps2,
        double emergencyBrakeMps2,
        double hardBrakeMps2,
        double incidentBrakeMps2,
        double incidentComboBrakeMps2,
        double speedViolationRatio,
        int violationSustainTicks,
        double speedingEpisodeChance,
        int speedingEpisodeTicks,
        double speedingFactorMax,
        int stopDwellTicksMin,
        int stopDwellTicksMax,
        long seed) implements java.io.Serializable {

    public static SimulatorConfig fromEnvironment() {
        return new SimulatorConfig(
                envInt("TESSERA_VEHICLE_COUNT", 24),
                envLong("TESSERA_TICK_MILLIS", 1000L),
                envDouble("TESSERA_MAX_SPEED_MPS", 25.0),
                envDouble("TESSERA_MAX_ACCEL", 1.3),
                envDouble("TESSERA_COMFORT_BRAKE", 2.4),
                envDouble("TESSERA_RISKY_BRAKE", 4.5),
                envDouble("TESSERA_EMERGENCY_BRAKE", 8.0),
                envDouble("TESSERA_HARD_BRAKE_THRESHOLD", 3.5),
                // Calibrated against the label the model is asked to predict, not
                // against the per-reading rate, because the two are very different
                // numbers. At 6.0/5.0 an incident is 0.33% of readings, which sounds
                // rare and is not: compounded over the 300 readings in a five-minute
                // window it makes 40% of training rows positive, and "will this
                // vehicle brake hard in the next five minutes" becomes a coin flip
                // that a majority-class guess already answers 60% of the time.
                //
                // At 6.5/5.8 an incident is 0.04% of readings — roughly 1.4 per
                // vehicle-hour, which is the right order for severe braking in real
                // telematics — and 10% of training rows are positive. Past 7.0 the
                // deceleration distribution runs out and nothing qualifies at all.
                envDouble("TESSERA_INCIDENT_BRAKE_THRESHOLD", 6.5),
                envDouble("TESSERA_INCIDENT_COMBO_THRESHOLD", 5.8),
                envDouble("TESSERA_SPEED_VIOLATION_RATIO", 1.15),
                envInt("TESSERA_VIOLATION_SUSTAIN_TICKS", 3),
                envDouble("TESSERA_SPEEDING_EPISODE_CHANCE", 0.010),
                envInt("TESSERA_SPEEDING_EPISODE_TICKS", 45),
                envDouble("TESSERA_SPEEDING_FACTOR_MAX", 1.45),
                envInt("TESSERA_STOP_DWELL_MIN", 10),
                envInt("TESSERA_STOP_DWELL_MAX", 40),
                envLong("TESSERA_SEED", 20260912L));
    }

    /** Seconds represented by one tick. */
    public double tickSeconds() {
        return tickMillis / 1000.0;
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static int envInt(String key, int fallback) {
        String v = env(key);
        return v == null ? fallback : Integer.parseInt(v);
    }

    private static long envLong(String key, long fallback) {
        String v = env(key);
        return v == null ? fallback : Long.parseLong(v);
    }

    private static double envDouble(String key, double fallback) {
        String v = env(key);
        return v == null ? fallback : Double.parseDouble(v);
    }
}
