package com.tessera.risk.producer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.tessera.risk.common.model.DriverDirectory;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.common.road.RoadNetwork;

/**
 * The fleet motion engine: holds every simulated vehicle and advances them one
 * tick at a time.
 *
 * <p>This is deliberately free of Kafka, files or wall-clock timing. The live
 * producer drives it at one tick per second to feed the stream; the offline
 * generator drives it as fast as the CPU allows to build the historical archive
 * used for model training. Both therefore produce data from identical physics and
 * identical behaviour, which is what makes a model trained on the archive valid
 * when scored against the live stream.
 */
public final class FleetSimulator {

    private final List<SimulatedVehicle> vehicles = new ArrayList<>();
    private final SimulatorConfig config;
    private final RoadNetwork network;

    public FleetSimulator(RoadNetwork network, SimulatorConfig config) {
        this.network = network;
        this.config = config;

        RoutePlanner planner = new RoutePlanner(
                network, config.comfortBrakeMps2(), config.maxSpeedMps());
        int[] pool = planner.roundTripPool();
        if (pool.length < 2) {
            throw new IllegalStateException(
                    "Road network has no mutually reachable node pool; cannot simulate");
        }

        Random seedRnd = new Random(config.seed());
        for (int i = 0; i < config.vehicleCount(); i++) {
            String vehicleId = String.format("VEH-%03d", i + 1);
            String driverId = String.format("DRV-%03d", i + 1);
            RiskTier tier = assignTier(i);
            // Each vehicle gets its own RNG stream so that adding or removing a
            // vehicle does not perturb the behaviour of the others.
            Random vehicleRnd = new Random(seedRnd.nextLong());
            vehicles.add(new SimulatedVehicle(
                    vehicleId, driverId, tier, network, planner, config, pool, vehicleRnd));
        }
    }

    /**
     * Fleet composition: a realistic skew rather than an even split — most drivers
     * are unremarkable, a minority are consistently risky. This also keeps the
     * prediction target rare, which is the honest situation and the one that makes
     * evaluating against a baseline meaningful.
     */
    private static RiskTier assignTier(int index) {
        int bucket = index % 10;
        if (bucket < 5) {
            return RiskTier.SAFE;
        }
        return bucket < 8 ? RiskTier.AVERAGE : RiskTier.RISKY;
    }

    /** Advance every vehicle by one tick and return the resulting readings. */
    public List<TelemetryEvent> tick(long nowEpochMs) {
        List<TelemetryEvent> batch = new ArrayList<>(vehicles.size());
        for (SimulatedVehicle vehicle : vehicles) {
            batch.add(vehicle.advance(nowEpochMs));
        }
        return batch;
    }

    public int vehicleCount() {
        return vehicles.size();
    }

    public String areaName() {
        return network.areaName();
    }

    /** Driver roster, for seeding the driver_profile reference table. */
    public List<DriverProfile> roster() {
        List<DriverProfile> out = new ArrayList<>(vehicles.size());
        for (int i = 0; i < vehicles.size(); i++) {
            SimulatedVehicle v = vehicles.get(i);
            String driverId = String.format("DRV-%03d", i + 1);
            out.add(new DriverProfile(
                    v.vehicleId(), driverId, DriverDirectory.nameFor(driverId), v.riskTier()));
        }
        return out;
    }

    /** Static reference data about a driver — written once to HBase, not streamed. */
    public record DriverProfile(String vehicleId, String driverId, String driverName,
                                RiskTier riskTier) { }
}
