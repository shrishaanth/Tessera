package com.tessera.risk.simulation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tessera.risk.common.model.EventType;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.common.road.RoadNetwork;
import com.tessera.risk.common.road.RoadNetworkLoader;

/**
 * Ad-hoc: prints the event distribution so the data can be inspected, not guessed at.
 *
 * <p>Also reports the balance of the machine-learning label — whether an incident
 * occurs for a vehicle in the window <em>after</em> the one being described. Per-reading
 * rates say almost nothing about that: a per-reading rate of a third of a percent sounds
 * rare, but compounded over the three hundred readings in a five-minute window it is
 * near-certain. The label balance is the number that decides whether the prediction task
 * is worth posing at all, so it is measured rather than inferred.
 */
public final class DistributionDiagnostic {

    /** Readings per training window; five minutes at the default one-second tick. */
    private static final int WINDOW_TICKS = 300;

    public static void main(String[] args) {
        int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 3600;
        RoadNetwork net = RoadNetworkLoader.fromClasspath("roadgraph/roadgraph.json");
        SimulatorConfig cfg = SimulatorConfig.fromEnvironment();
        FleetSimulator sim = new FleetSimulator(net, cfg);

        Map<EventType, Long> byType = new EnumMap<>(EventType.class);
        Map<RiskTier, long[]> byTier = new EnumMap<>(RiskTier.class);
        for (RiskTier t : RiskTier.values()) byTier.put(t, new long[3]); // readings, precursors, incidents
        long total = 0;
        double maxSpeed = 0;

        // Per vehicle, one entry per tumbling window holding that window's incident count.
        Map<String, List<Integer>> incidentsPerWindow = new HashMap<>();
        Map<String, RiskTier> tierOf = new HashMap<>();

        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < ticks; i++) {
            int windowIndex = i / WINDOW_TICKS;
            List<TelemetryEvent> frame = sim.tick(t0 + i * cfg.tickMillis());
            for (TelemetryEvent e : frame) {
                total++;
                byType.merge(e.eventType(), 1L, Long::sum);
                long[] tier = byTier.get(e.riskTier());
                tier[0]++;
                if (e.eventType() == EventType.HARD_BRAKE || e.eventType() == EventType.SPEED_VIOLATION) tier[1]++;
                if (e.eventType() == EventType.INCIDENT) tier[2]++;
                maxSpeed = Math.max(maxSpeed, e.speedKph());

                tierOf.put(e.vehicleId(), e.riskTier());
                List<Integer> windows = incidentsPerWindow.computeIfAbsent(
                        e.vehicleId(), k -> new ArrayList<>());
                while (windows.size() <= windowIndex) {
                    windows.add(0);
                }
                if (e.eventType() == EventType.INCIDENT) {
                    windows.set(windowIndex, windows.get(windowIndex) + 1);
                }
            }
        }

        System.out.printf("%d vehicles x %d ticks = %,d readings%n", sim.vehicleCount(), ticks, total);
        System.out.printf("max speed observed: %.1f km/h%n%n", maxSpeed);
        System.out.println("event type            count      share");
        for (EventType et : EventType.values()) {
            long c = byType.getOrDefault(et, 0L);
            System.out.printf("%-18s %,10d  %8.3f%%%n", et, c, 100.0 * c / total);
        }
        System.out.println();
        System.out.println("tier      readings   precursor%   incident%");
        for (RiskTier t : RiskTier.values()) {
            long[] v = byTier.get(t);
            if (v[0] == 0) continue;
            System.out.printf("%-9s %,9d   %8.3f%%   %8.3f%%%n",
                    t, v[0], 100.0 * v[1] / v[0], 100.0 * v[2] / v[0]);
        }

        reportLabelBalance(incidentsPerWindow, tierOf);
    }

    /**
     * The fraction of training rows whose label is positive.
     *
     * <p>A row is one (vehicle, window) pair; it is positive when the vehicle has at
     * least one incident in the <em>following</em> window. The last window of each
     * vehicle has no following window and is not a usable row, so it is excluded —
     * exactly as the batch feature job must exclude it.
     */
    private static void reportLabelBalance(Map<String, List<Integer>> incidentsPerWindow,
                                           Map<String, RiskTier> tierOf) {
        Map<RiskTier, long[]> byTier = new EnumMap<>(RiskTier.class);
        for (RiskTier t : RiskTier.values()) {
            byTier.put(t, new long[2]); // rows, positives
        }
        long rows = 0;
        long positives = 0;

        for (Map.Entry<String, List<Integer>> entry : incidentsPerWindow.entrySet()) {
            List<Integer> windows = entry.getValue();
            long[] tier = byTier.get(tierOf.get(entry.getKey()));
            for (int w = 0; w + 1 < windows.size(); w++) {
                boolean positive = windows.get(w + 1) > 0;
                rows++;
                tier[0]++;
                if (positive) {
                    positives++;
                    tier[1]++;
                }
            }
        }

        System.out.println();
        System.out.printf("ML label: incident in the NEXT %d-reading window%n", WINDOW_TICKS);
        if (rows == 0) {
            System.out.println("  not enough windows to form a labelled row");
            return;
        }
        System.out.printf("  %,d labelled rows, %,d positive (%.1f%%)%n",
                rows, positives, 100.0 * positives / rows);
        System.out.println("  tier      rows   positive%");
        for (RiskTier t : RiskTier.values()) {
            long[] v = byTier.get(t);
            if (v[0] == 0) continue;
            System.out.printf("  %-9s %5d   %7.1f%%%n", t, v[0], 100.0 * v[1] / v[0]);
        }
        // A baseline that always guesses the majority class is what any model has to
        // beat. Printed here so the comparison is not flattering by omission.
        double majority = Math.max(positives, rows - positives) / (double) rows;
        System.out.printf("  majority-class baseline accuracy: %.1f%%%n", 100.0 * majority);
    }

    private DistributionDiagnostic() { }
}
