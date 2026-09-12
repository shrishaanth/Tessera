package com.tessera.risk.reporting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.tessera.risk.reporting.hbase.DriverProfileRepository;
import com.tessera.risk.reporting.hbase.VehicleRiskRepository;
import com.tessera.risk.reporting.model.FleetSnapshot;
import com.tessera.risk.reporting.model.VehicleRisk;

/**
 * Assembles the fleet's current state from HBase (FR-5.1).
 *
 * <h2>The read pattern, and its cost</h2>
 * HBase has no joins and no aggregation, so "the latest window for every vehicle"
 * is the roster scan plus one bounded scan per vehicle. For a fleet of a few dozen
 * that is a few dozen cheap lookups and comfortably within a request; it is O(fleet
 * size) and independent of how much history exists, which is the property that
 * matters.
 *
 * <p>It would not scale to thousands of vehicles as written — that would want the
 * streaming job to maintain a single "current fleet state" row, trading a little
 * write amplification for one read. The honest description of what is here is that
 * it suits this fleet and would be reshaped for a larger one.
 */
@Service
public class FleetService {

    private final VehicleRiskRepository vehicleRisk;
    private final DriverProfileRepository driverProfiles;
    private final ReportingProperties properties;

    public FleetService(VehicleRiskRepository vehicleRisk,
                        DriverProfileRepository driverProfiles,
                        ReportingProperties properties) {
        this.vehicleRisk = vehicleRisk;
        this.driverProfiles = driverProfiles;
        this.properties = properties;
    }

    /** Every vehicle's newest window, ordered by vehicle id. */
    public FleetSnapshot snapshot() throws IOException {
        Map<String, String> driverNames = driverProfiles.driverNamesByVehicle();

        List<VehicleRisk> vehicles = new ArrayList<>(driverNames.size());
        for (String vehicleId : driverNames.keySet()) {
            // A vehicle in the roster with no risk row yet is normal — it has
            // reported, which is how it got into driver_profile, but no window has
            // closed for it. Skipping it is better than a row of zeroes that reads
            // as a vehicle driving perfectly.
            vehicleRisk.latest(vehicleId, driverNames).ifPresent(vehicles::add);
        }

        boolean anyModelScored = vehicles.stream().anyMatch(VehicleRisk::modelScored);
        return new FleetSnapshot(
                System.currentTimeMillis(), vehicles.size(), anyModelScored, vehicles);
    }

    /** One vehicle's recent windows, newest first. */
    public List<VehicleRisk> history(String vehicleId, Integer limit) throws IOException {
        int windows = limit == null || limit <= 0 ? properties.getHistoryWindows() : limit;
        Map<String, String> driverNames = driverProfiles.driverNamesByVehicle();
        return vehicleRisk.history(vehicleId, windows, driverNames);
    }

    /** One vehicle's newest window, or empty if it has never reported. */
    public Optional<VehicleRisk> latest(String vehicleId) throws IOException {
        return vehicleRisk.latest(vehicleId, driverProfiles.driverNamesByVehicle());
    }
}
