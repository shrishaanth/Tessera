package com.tessera.risk.reporting.model;

import java.util.List;

/**
 * The whole fleet's latest risk state.
 *
 * @param generatedAt when the service assembled this, epoch millis
 * @param modelScored whether any vehicle carries a model prediction; false on a
 *                    stack where training has not run yet, which lets the dashboard
 *                    explain the absence rather than render blanks
 * @param vehicles    one row per vehicle, newest window each
 */
public record FleetSnapshot(
        long generatedAt,
        int vehicleCount,
        boolean modelScored,
        List<VehicleRisk> vehicles) { }
