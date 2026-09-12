package com.tessera.risk.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.geo.GeoMath;
import com.tessera.risk.common.model.EventType;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.common.road.RoadNetwork;
import com.tessera.risk.common.road.RoadNetworkLoader;

/**
 * Validates the simulated fleet.
 *
 * <p>Two things are being guarded here, and the second matters as much as the
 * first. The motion must be physically plausible — no teleports, bounded speed —
 * because implausible data would invalidate every metric computed downstream. And
 * the unsafe-behaviour signal must actually be present and correlated with driver
 * tier, because if risky drivers were indistinguishable from safe ones, the
 * machine-learning task further down the pipeline would be unlearnable and the
 * whole system would be measuring noise.
 */
class FleetSimulatorTest {

    private static final int TICKS = 2000;

    private static RoadNetwork network;
    private static SimulatorConfig config;
    private static List<List<TelemetryEvent>> frames;

    @BeforeAll
    static void simulate() {
        network = RoadNetworkLoader.fromClasspath("roadgraph/roadgraph.json");
        config = SimulatorConfig.fromEnvironment();
        FleetSimulator simulator = new FleetSimulator(network, config);

        frames = new ArrayList<>(TICKS);
        long t = 1_700_000_000_000L;
        for (int i = 0; i < TICKS; i++) {
            frames.add(simulator.tick(t));
            t += config.tickMillis();
        }
    }

    @Test
    void everyTickReportsEveryVehicle() {
        assertThat(frames).hasSize(TICKS);
        for (List<TelemetryEvent> frame : frames) {
            assertThat(frame).hasSize(config.vehicleCount());
        }
        assertThat(frames.get(0).stream().map(TelemetryEvent::vehicleId).distinct())
                .hasSize(config.vehicleCount());
    }

    @Test
    void noVehicleEverTeleports() {
        double limit = config.maxSpeedMps() * config.tickSeconds() * 1.10;
        Map<String, TelemetryEvent> previous = new HashMap<>();
        double worst = 0.0;
        String worstVehicle = null;

        for (List<TelemetryEvent> frame : frames) {
            for (TelemetryEvent e : frame) {
                TelemetryEvent prev = previous.put(e.vehicleId(), e);
                if (prev == null) {
                    continue;
                }
                double moved = GeoMath.haversineMeters(prev.lat(), prev.lon(), e.lat(), e.lon());
                if (moved > worst) {
                    worst = moved;
                    worstVehicle = e.vehicleId();
                }
            }
        }
        assertThat(worst)
                .as("largest single-tick displacement (%s moved %.1f m)", worstVehicle, worst)
                .isLessThanOrEqualTo(limit);
    }

    @Test
    void speedNeverExceedsThePhysicalCeiling() {
        double ceilingKph = config.maxSpeedMps() * 3.6 * 1.02;
        for (List<TelemetryEvent> frame : frames) {
            for (TelemetryEvent e : frame) {
                assertThat(e.speedKph())
                        .as("%s speed", e.vehicleId())
                        .isBetween(0.0, ceilingKph);
            }
        }
    }

    @Test
    void reportedSpeedIsConsistentWithActualDisplacement() {
        Map<String, TelemetryEvent> previous = new HashMap<>();
        for (List<TelemetryEvent> frame : frames) {
            for (TelemetryEvent e : frame) {
                TelemetryEvent prev = previous.put(e.vehicleId(), e);
                if (prev == null) {
                    continue;
                }
                double moved = GeoMath.haversineMeters(prev.lat(), prev.lon(), e.lat(), e.lon());
                double claimedMps = Math.max(prev.speedKph(), e.speedKph()) / 3.6;
                assertThat(moved)
                        .as("%s moved %.1f m while reporting at most %.1f m/s",
                                e.vehicleId(), moved, claimedMps)
                        .isLessThanOrEqualTo(claimedMps * config.tickSeconds() + 2.0);
            }
        }
    }

    @Test
    void everyReadingCarriesASegmentAndItsRealSpeedLimit() {
        for (List<TelemetryEvent> frame : frames) {
            for (TelemetryEvent e : frame) {
                assertThat(e.segmentId()).startsWith("SEG-");
                assertThat(e.speedLimitKph()).isBetween(5.0, 130.0);
                assertThat(e.riskTier()).isNotNull();
                assertThat(e.eventType()).isNotNull();
            }
        }
    }

    @Test
    void allPositionsStayWithinTheRoadNetworkArea() {
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        for (int n = 0; n < network.nodeCount(); n++) {
            minLat = Math.min(minLat, network.lat(n));
            maxLat = Math.max(maxLat, network.lat(n));
            minLon = Math.min(minLon, network.lon(n));
            maxLon = Math.max(maxLon, network.lon(n));
        }
        for (List<TelemetryEvent> frame : frames) {
            for (TelemetryEvent e : frame) {
                assertThat(e.lat()).isBetween(minLat - 1e-6, maxLat + 1e-6);
                assertThat(e.lon()).isBetween(minLon - 1e-6, maxLon + 1e-6);
            }
        }
    }

    @Test
    void theFleetIsActuallyDriving() {
        int moving = 0;
        int samples = 0;
        Map<String, TelemetryEvent> previous = new HashMap<>();
        for (List<TelemetryEvent> frame : frames) {
            for (TelemetryEvent e : frame) {
                TelemetryEvent prev = previous.put(e.vehicleId(), e);
                if (prev == null) {
                    continue;
                }
                samples++;
                if (GeoMath.haversineMeters(prev.lat(), prev.lon(), e.lat(), e.lon()) > 0.5) {
                    moving++;
                }
            }
        }
        assertThat(100.0 * moving / samples)
                .as("percentage of readings where the vehicle is in motion")
                .isGreaterThan(30.0);
    }

    @Test
    void allThreeBehaviouralEventTypesOccur() {
        Map<EventType, Long> counts = countByType();
        assertThat(counts.getOrDefault(EventType.SPEED_VIOLATION, 0L))
                .as("speed violations").isPositive();
        assertThat(counts.getOrDefault(EventType.HARD_BRAKE, 0L))
                .as("hard brakes").isPositive();
        assertThat(counts.getOrDefault(EventType.INCIDENT, 0L))
                .as("incidents").isPositive();
    }

    @Test
    void incidentsAreRarerThanTheirPrecursors() {
        // The prediction target must be the rare, severe outcome. If incidents were
        // as common as hard brakes, predicting them would be trivial and the model
        // would be demonstrating nothing.
        Map<EventType, Long> counts = countByType();
        long incidents = counts.getOrDefault(EventType.INCIDENT, 0L);
        long precursors = counts.getOrDefault(EventType.HARD_BRAKE, 0L)
                + counts.getOrDefault(EventType.SPEED_VIOLATION, 0L);
        assertThat(incidents).isLessThan(precursors);

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        assertThat(100.0 * incidents / total)
                .as("incidents as a percentage of all readings")
                .isLessThan(5.0);
    }

    @Test
    void aSlicedFleetDrivesIdenticallyToAWholeOne() {
        // The offline generator simulates different vehicles on different executors.
        // That is only valid if splitting the fleet changes nothing about what each
        // vehicle does — so this drives the whole fleet, then drives it again in two
        // halves, and requires every reading to match exactly.
        //
        // The failure this guards against is quiet. If a slice did not advance the
        // master seed stream for the vehicles it skips, every vehicle after the split
        // would get a different seed and drive a different route. The archive would
        // still look entirely plausible; it simply would not be the data the live
        // producer generates, and a model trained on it would be fitted to a fleet
        // that does not exist.
        int fleetSize = config.vehicleCount();
        int half = fleetSize / 2;

        FleetSimulator whole = new FleetSimulator(network, config);
        FleetSimulator lower = new FleetSimulator(network, config, 0, half);
        FleetSimulator upper = new FleetSimulator(network, config, half, fleetSize);

        long t = 1_700_000_000_000L;
        for (int i = 0; i < 200; i++, t += config.tickMillis()) {
            List<TelemetryEvent> expected = whole.tick(t);
            List<TelemetryEvent> actual = new ArrayList<>(lower.tick(t));
            actual.addAll(upper.tick(t));

            assertThat(actual)
                    .as("tick %d: sliced fleet must reproduce the whole fleet exactly", i)
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void theTrainingLabelIsImbalancedButNotDegenerate() {
        // The per-reading incident rate is not the number that matters. The model is
        // asked whether an incident occurs in the *next* window, and a rate that looks
        // rare per reading compounds over the hundreds of readings in a window: at a
        // third of a percent, two in five windows end up positive and the question
        // becomes a coin flip. This asserts the property the whole ML stage rests on,
        // and it is one a per-reading assertion cannot see.
        double positiveRate = 100.0 * positiveLabelRate();

        // Rare enough that accuracy is a worthless metric and precision and recall
        // have to be reported instead.
        assertThat(positiveRate)
                .as("percentage of labelled rows that are positive")
                .isLessThan(25.0);
        // But not so rare that a training set of any reasonable size holds too few
        // positives to learn from.
        assertThat(positiveRate)
                .as("percentage of labelled rows that are positive")
                .isGreaterThan(2.0);
    }

    @Test
    void knowingOnlyTheDriverTierDoesNotSolveTheTask() {
        // If any tier crossed 50%, a classifier could reach the majority-class
        // baseline knowing nothing but who is driving, and every later claim that the
        // model learned something from behaviour would be unfalsifiable. The tiers
        // must shift the odds without settling them.
        Map<RiskTier, long[]> byTier = labelCountsByTier();
        for (Map.Entry<RiskTier, long[]> entry : byTier.entrySet()) {
            long[] counts = entry.getValue();
            if (counts[0] == 0) {
                continue;
            }
            assertThat(100.0 * counts[1] / counts[0])
                    .as("positive rate for %s, which must not decide the label alone",
                            entry.getKey())
                    .isLessThan(50.0);
        }
    }

    /** Windows of {@code WINDOW_TICKS} readings, labelled by the next window's incidents. */
    private static double positiveLabelRate() {
        long rows = 0;
        long positives = 0;
        for (long[] counts : labelCountsByTier().values()) {
            rows += counts[0];
            positives += counts[1];
        }
        return rows == 0 ? 0.0 : (double) positives / rows;
    }

    /** Per tier: {@code [labelled rows, positive rows]}. */
    private static Map<RiskTier, long[]> labelCountsByTier() {
        int windowTicks = 300;
        Map<String, List<Integer>> incidentsPerWindow = new HashMap<>();
        Map<String, RiskTier> tierOf = new HashMap<>();

        for (int tick = 0; tick < frames.size(); tick++) {
            int window = tick / windowTicks;
            for (TelemetryEvent event : frames.get(tick)) {
                tierOf.put(event.vehicleId(), event.riskTier());
                List<Integer> windows = incidentsPerWindow.computeIfAbsent(
                        event.vehicleId(), k -> new ArrayList<>());
                while (windows.size() <= window) {
                    windows.add(0);
                }
                if (event.eventType() == EventType.INCIDENT) {
                    windows.set(window, windows.get(window) + 1);
                }
            }
        }

        Map<RiskTier, long[]> byTier = new EnumMap<>(RiskTier.class);
        for (RiskTier tier : RiskTier.values()) {
            byTier.put(tier, new long[2]);
        }
        for (Map.Entry<String, List<Integer>> entry : incidentsPerWindow.entrySet()) {
            long[] counts = byTier.get(tierOf.get(entry.getKey()));
            List<Integer> windows = entry.getValue();
            // The final window has no successor, so it cannot be labelled — the same
            // exclusion the batch feature job has to make.
            for (int w = 0; w + 1 < windows.size(); w++) {
                counts[0]++;
                if (windows.get(w + 1) > 0) {
                    counts[1]++;
                }
            }
        }
        return byTier;
    }

    @Test
    void riskyDriversProduceMoreUnsafeBehaviourThanSafeOnes() {
        // This is the correlation the machine-learning stage depends on. If it did
        // not hold, no model could beat a trivial baseline and the pipeline would
        // be measuring noise.
        Map<RiskTier, Long> events = new EnumMap<>(RiskTier.class);
        Map<RiskTier, Long> readings = new EnumMap<>(RiskTier.class);
        for (List<TelemetryEvent> frame : frames) {
            for (TelemetryEvent e : frame) {
                readings.merge(e.riskTier(), 1L, Long::sum);
                if (e.isBehaviouralEvent()) {
                    events.merge(e.riskTier(), 1L, Long::sum);
                }
            }
        }
        double riskyRate = rate(events, readings, RiskTier.RISKY);
        double averageRate = rate(events, readings, RiskTier.AVERAGE);
        double safeRate = rate(events, readings, RiskTier.SAFE);

        assertThat(riskyRate)
                .as("RISKY event rate %.4f should exceed AVERAGE %.4f", riskyRate, averageRate)
                .isGreaterThan(averageRate);
        assertThat(averageRate)
                .as("AVERAGE event rate %.4f should exceed SAFE %.4f", averageRate, safeRate)
                .isGreaterThan(safeRate);
    }

    @Test
    void telemetrySurvivesAJsonRoundTrip() {
        TelemetryEvent original = frames.get(10).get(0);
        TelemetryEvent restored = TelemetryEvent.fromJson(original.toJson());
        assertThat(restored).isEqualTo(original);
    }

    private static double rate(Map<RiskTier, Long> events, Map<RiskTier, Long> readings,
                               RiskTier tier) {
        long r = readings.getOrDefault(tier, 0L);
        return r == 0 ? 0.0 : (double) events.getOrDefault(tier, 0L) / r;
    }

    private static Map<EventType, Long> countByType() {
        Map<EventType, Long> counts = new EnumMap<>(EventType.class);
        for (List<TelemetryEvent> frame : frames) {
            for (TelemetryEvent e : frame) {
                counts.merge(e.eventType(), 1L, Long::sum);
            }
        }
        return counts;
    }
}
