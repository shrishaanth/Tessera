package com.tessera.risk.simulation;

import java.util.Random;

import com.tessera.risk.common.geo.GeoMath;
import com.tessera.risk.common.model.EventType;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.common.road.RoadNetwork;

/**
 * One vehicle: its position on the road network, its physical state, and the
 * driver behaviour layered on top.
 *
 * <p>Motion is integrated in sub-steps so that acceleration stays bounded even
 * when several short OSM segments are crossed within one tick — the road graph
 * has a median edge length of a few metres, so integrating once per segment
 * crossing would let acceleration stack far beyond anything a vehicle can do.
 *
 * <p>Unsafe behaviour is <em>emergent</em>. A driver in a speeding episode targets
 * a speed above the posted limit. The route plan's braking profile was computed
 * for the lawful limit, so on reaching a corner or a stop the deceleration
 * actually required exceeds the comfortable rate — and that is recorded as a hard
 * brake, or if severe enough, an incident. Nothing is injected directly; the
 * precursor events and the incidents share a single cause, which is what makes
 * the prediction task genuinely learnable rather than a coin flip.
 */
final class SimulatedVehicle {

    /** Minimum straight-line distance to a new destination, metres. */
    private static final double MIN_LEG_DISTANCE_M = 400.0;
    /** Distance floor used when computing required braking, metres. */
    private static final double BRAKE_LOOKAHEAD_M = 3.0;

    private final String vehicleId;
    private final String driverId;
    private final RiskTier riskTier;
    private final RoadNetwork network;
    private final RoutePlanner planner;
    private final SimulatorConfig config;
    private final Random rnd;
    private final int[] pool;
    private final int depot;

    private RoutePlanner.Leg leg;
    private int segIndex;
    private double alongM;
    private double speedMps;

    /** Where the vehicle actually is when it has no active leg. */
    private int parkedNode;
    private int lastEdge = -1;
    private double lastBearing;

    private boolean needsNewLeg = true;
    private int dwellTicksRemaining;
    private int speedingTicksRemaining;
    private double speedingFactor = 1.0;
    private int consecutiveOverLimitTicks;
    /** Hardest deceleration seen within the current tick, metres per second squared. */
    private double peakDecelMps2;

    SimulatedVehicle(String vehicleId, String driverId, RiskTier riskTier,
                     RoadNetwork network, RoutePlanner planner, SimulatorConfig config,
                     int[] pool, Random rnd) {
        this.vehicleId = vehicleId;
        this.driverId = driverId;
        this.riskTier = riskTier;
        this.network = network;
        this.planner = planner;
        this.config = config;
        this.pool = pool;
        this.rnd = rnd;
        this.depot = pool[rnd.nextInt(pool.length)];
        this.parkedNode = depot;
        // Plan the first leg up front so that position, segment and speed limit are
        // well defined from the very first reading, then hold it while the staggered
        // start runs down — otherwise the fleet would all pull away in lockstep.
        planNextLeg();
        this.dwellTicksRemaining = rnd.nextInt(20);
    }

    String vehicleId() {
        return vehicleId;
    }

    RiskTier riskTier() {
        return riskTier;
    }

    /** Advance one tick and produce the resulting telemetry reading. */
    TelemetryEvent advance(long nowEpochMs) {
        peakDecelMps2 = 0.0;
        updateSpeedingEpisode();

        if (dwellTicksRemaining > 0) {
            dwellTicksRemaining--;
            speedMps = 0.0;
        } else {
            if (needsNewLeg) {
                planNextLeg();
            }
            if (leg != null) {
                integrateMotion();
            }
        }

        int edge = currentEdge();
        double limitKph = edge >= 0 ? network.speedLimitKph(edge) : 0.0;
        double speedKph = speedMps * 3.6;

        boolean overLimit = limitKph > 0 && speedKph > limitKph * config.speedViolationRatio();
        consecutiveOverLimitTicks = overLimit ? consecutiveOverLimitTicks + 1 : 0;
        boolean sustainedViolation = consecutiveOverLimitTicks >= config.violationSustainTicks();

        EventType type = classify(peakDecelMps2, sustainedViolation);
        double severity = severityOf(type, peakDecelMps2, speedKph, limitKph);

        double[] position = currentPosition();
        return new TelemetryEvent(
                vehicleId, driverId, riskTier,
                position[0], position[1],
                round(speedKph, 2), round(currentBearing(), 1),
                edge >= 0 ? network.segmentId(edge) : "SEG-NONE",
                limitKph,
                type, round(severity, 3),
                nowEpochMs);
    }

    /**
     * Classify from the hardest deceleration reached <em>within</em> the tick, not
     * the average across it. A real telematics unit samples acceleration far faster
     * than it reports position, so a brief hard brake is something it genuinely
     * sees; averaging over a whole second would hide it.
     */
    private EventType classify(double decelMps2, boolean sustainedViolation) {
        if (decelMps2 >= config.incidentBrakeMps2()) {
            return EventType.INCIDENT;
        }
        if (decelMps2 >= config.incidentComboBrakeMps2() && sustainedViolation) {
            // Braking severely while already speeding. The threshold sits above the
            // ordinary hard-brake one on purpose: hard braking is the normal
            // consequence of a speeding episode, so upgrading every instance of it
            // would make the incident as common as its own precursor and leave
            // nothing rare to predict.
            return EventType.INCIDENT;
        }
        if (decelMps2 >= config.hardBrakeMps2()) {
            return EventType.HARD_BRAKE;
        }
        if (sustainedViolation) {
            return EventType.SPEED_VIOLATION;
        }
        return EventType.NORMAL;
    }

    private double severityOf(EventType type, double decelMps2, double speedKph, double limitKph) {
        return switch (type) {
            case HARD_BRAKE, INCIDENT ->
                    Math.min(1.0, decelMps2 / config.emergencyBrakeMps2());
            case SPEED_VIOLATION ->
                    limitKph <= 0 ? 0.0 : Math.min(1.0, (speedKph / limitKph - 1.0) / 0.5);
            case NORMAL -> 0.0;
        };
    }

    /**
     * A speeding episode is the single behavioural lever. Its likelihood scales
     * with the driver's tier, and while it runs the driver targets a speed above
     * the posted limit — from which the harsh braking follows physically.
     */
    private void updateSpeedingEpisode() {
        if (speedingTicksRemaining > 0) {
            speedingTicksRemaining--;
            if (speedingTicksRemaining == 0) {
                speedingFactor = 1.0;
            }
            return;
        }
        double chance = config.speedingEpisodeChance() * riskTier.eventPropensity();
        if (rnd.nextDouble() < chance) {
            speedingTicksRemaining = config.speedingEpisodeTicks();
            double floor = config.speedViolationRatio() + 0.02;
            speedingFactor = floor + rnd.nextDouble() * Math.max(0.0, config.speedingFactorMax() - floor);
        }
    }

    /** Integrate this tick's motion in fixed sub-steps. */
    private void integrateMotion() {
        final int subSteps = 10;
        final double dt = config.tickSeconds() / subSteps;

        boolean speeding = speedingFactor > 1.0;

        for (int step = 0; step < subSteps && leg != null; step++) {
            double segLen = leg.segLenM()[segIndex];
            double distToNode = Math.max(0.0, segLen - alongM);
            double nextCap = leg.nodeCapMps()[segIndex + 1];

            // The fastest one may be going here and still make the next node's cap.
            // A lawful driver holds themselves to comfortable braking; a speeding one
            // plans around a harsher rate they are willing to use — which is why
            // their braking comes out harsh rather than smooth. It is deliberately
            // short of the emergency limit: a driver who planned around maximum
            // braking would be at the threshold of losing control at every corner.
            double brakeBudget = speeding
                    ? config.riskyBrakeMps2()
                    : config.comfortBrakeMps2();
            double envelope = Math.sqrt(nextCap * nextCap + 2 * brakeBudget * distToNode);
            double desired = Math.min(leg.segLimitMps()[segIndex] * speedingFactor, envelope);

            double accel;
            if (speedMps > desired + 0.05) {
                // Slow down at the rate actually needed to meet the cap at the node.
                // Floor the distance before dividing by it. Evaluated at sub-metre
                // range this expression tends to infinity, which is an artifact of
                // sampling rather than physics: a driver approaching a stop has
                // already shed the speed by then, not applied unbounded braking in
                // the final centimetre. Without the floor every node approach would
                // register as a spike and drown the real events in noise.
                double brakingDistance = Math.max(distToNode, BRAKE_LOOKAHEAD_M);
                double required =
                        (speedMps * speedMps - nextCap * nextCap) / (2 * brakingDistance);
                accel = -clamp(required, config.comfortBrakeMps2(), config.emergencyBrakeMps2());
            } else if (speedMps < desired) {
                accel = config.maxAccelMps2();
            } else {
                accel = 0.0;
            }

            if (accel < 0) {
                peakDecelMps2 = Math.max(peakDecelMps2, -accel);
            }

            speedMps = Math.max(0.0, Math.min(config.maxSpeedMps(), speedMps + accel * dt));
            alongM += speedMps * dt;

            while (leg != null && alongM >= leg.segLenM()[segIndex]) {
                alongM -= leg.segLenM()[segIndex];
                segIndex++;
                if (segIndex >= leg.segmentCount()) {
                    arriveAtStop();
                    return;
                }
            }
        }
    }

    /**
     * Park at the end of the current leg. The leg is retained so that position,
     * segment and bearing stay well defined while stopped — a parked vehicle is
     * still standing on a real road, and reporting it as being nowhere would put
     * readings with no speed limit into the stream.
     */
    private void arriveAtStop() {
        int last = leg.segmentCount() - 1;
        segIndex = last;
        alongM = leg.segLenM()[last];
        lastEdge = leg.edges()[last];
        lastBearing = leg.segBearing()[last];
        parkedNode = leg.nodes()[leg.nodes().length - 1];
        speedMps = 0.0;
        needsNewLeg = true;
        dwellTicksRemaining = config.stopDwellTicksMin()
                + rnd.nextInt(Math.max(1, config.stopDwellTicksMax() - config.stopDwellTicksMin()));
    }

    /**
     * Plan the next leg from wherever the vehicle actually is. Destinations are
     * drawn far enough away to be a real drive: the pool covers a compact area, so
     * an unfiltered pick is frequently a few metres down the road, which would
     * leave the fleet dwelling far more than driving.
     */
    private void planNextLeg() {
        int from = parkedNode;
        double fromLat = network.lat(from);
        double fromLon = network.lon(from);

        for (int attempt = 0; attempt < 24; attempt++) {
            int to = rnd.nextDouble() < 0.20 ? depot : pool[rnd.nextInt(pool.length)];
            if (to == from) {
                continue;
            }
            double straightLine = GeoMath.haversineMeters(
                    fromLat, fromLon, network.lat(to), network.lon(to));
            // Relax the distance requirement as attempts run out, so a vehicle
            // parked near the edge of the area still finds somewhere to go.
            double required = attempt < 16 ? MIN_LEG_DISTANCE_M : 0.0;
            if (straightLine < required) {
                continue;
            }
            RoutePlanner.Leg planned = planner.plan(from, to, rnd);
            if (planned != null && planned.segmentCount() > 0) {
                leg = planned;
                segIndex = 0;
                alongM = 0.0;
                lastEdge = planned.edges()[0];
                lastBearing = planned.segBearing()[0];
                needsNewLeg = false;
                return;
            }
        }
        // Pool membership guarantees mutual reachability, so this is unreachable in
        // practice; wait rather than invent a position if it ever happens.
        dwellTicksRemaining = 5;
    }

    private int currentEdge() {
        if (leg == null || needsNewLeg) {
            return lastEdge;
        }
        return leg.edges()[segIndex];
    }

    private double currentBearing() {
        if (leg == null || needsNewLeg) {
            return lastBearing;
        }
        return leg.segBearing()[segIndex];
    }

    private double[] currentPosition() {
        if (leg == null) {
            return new double[] {network.lat(parkedNode), network.lon(parkedNode)};
        }
        int from = leg.nodes()[segIndex];
        int to = leg.nodes()[segIndex + 1];
        double f = Math.min(1.0, alongM / leg.segLenM()[segIndex]);
        return new double[] {
                network.lat(from) + (network.lat(to) - network.lat(from)) * f,
                network.lon(from) + (network.lon(to) - network.lon(from)) * f,
        };
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round(double v, int places) {
        double scale = Math.pow(10, places);
        return Math.round(v * scale) / scale;
    }
}
