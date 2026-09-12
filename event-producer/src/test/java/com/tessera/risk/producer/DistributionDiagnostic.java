package com.tessera.risk.producer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.tessera.risk.common.model.EventType;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.common.road.RoadNetwork;
import com.tessera.risk.common.road.RoadNetworkLoader;

/** Ad-hoc: prints the event distribution so the data can be inspected, not guessed at. */
public final class DistributionDiagnostic {
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

        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < ticks; i++) {
            List<TelemetryEvent> frame = sim.tick(t0 + i * cfg.tickMillis());
            for (TelemetryEvent e : frame) {
                total++;
                byType.merge(e.eventType(), 1L, Long::sum);
                long[] tier = byTier.get(e.riskTier());
                tier[0]++;
                if (e.eventType() == EventType.HARD_BRAKE || e.eventType() == EventType.SPEED_VIOLATION) tier[1]++;
                if (e.eventType() == EventType.INCIDENT) tier[2]++;
                maxSpeed = Math.max(maxSpeed, e.speedKph());
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
    }
    private DistributionDiagnostic() { }
}
