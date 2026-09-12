package com.tessera.risk.reporting.model;

import java.util.List;

/**
 * Where the data comes from (FR-6.1).
 *
 * <p>Served by the API rather than hard-coded into the dashboard so that the
 * disclosure cannot drift out of step with the system that generates the data, and
 * so any client — the dashboard, a curious HTTP request — gets the same statement.
 * FR-6.2 requires it not be presented as a footnote; supplying it as first-class
 * content rather than as a comment in a template is the part of that this service
 * can enforce.
 */
public record DataSourceInfo(
        boolean simulated,
        String headline,
        List<String> details) {

    public static DataSourceInfo simulated(String areaName) {
        return new DataSourceInfo(true,
                "All vehicle data on this dashboard is simulated.",
                List.of(
                        "Vehicles are driven along a real OpenStreetMap road network"
                                + (areaName == null || areaName.isBlank()
                                        ? "" : " covering " + areaName) + ".",
                        "Motion obeys real posted speed limits and enforced physical "
                                + "acceleration, braking and cornering limits.",
                        "Unsafe driving is emergent, not injected: a driver's risk tier "
                                + "scales only how often a speeding episode begins, and the "
                                + "hard braking and incidents follow from travelling above "
                                + "the speed the route was planned for.",
                        "No physical vehicles, telematics feed, or personal data are "
                                + "involved. Driver names are invented and describe no real "
                                + "person.",
                        "No public dataset of commercial fleet telemetry exists, which is "
                                + "why this data is generated rather than collected."));
    }
}
