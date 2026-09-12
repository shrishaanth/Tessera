package com.tessera.risk.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A notification that one vehicle has crossed a risk threshold within a window.
 *
 * <p>Published to {@code vehicle.alerts} by the streaming job and consumed by the
 * reporting service, which pushes it to dashboard clients. It lives in
 * {@code common} rather than in the streaming module precisely because two
 * separate services have to agree on its shape.
 *
 * <p>Unlike {@link TelemetryEvent} this is a low-volume topic — a handful of
 * messages a minute — so it carries denormalised context (driver, counts, a
 * human-readable reason) that a consumer would otherwise have to go and fetch.
 *
 * @param vehicleId           vehicle the alert concerns; also the Kafka key
 * @param driverId            driver assigned to that vehicle
 * @param riskTier            the driver's standing profile
 * @param windowStart         start of the aggregation window, epoch millis
 * @param windowEnd           end of the aggregation window, epoch millis
 * @param riskScore           composite score for the window, 0-100
 * @param hardBrakeCount      hard-brake readings in the window
 * @param speedViolationCount speed-violation readings in the window
 * @param incidentCount       incident readings in the window
 * @param readingCount        readings the score was computed over; the score is a
 *                            rate, so without its denominator a consumer cannot
 *                            tell twelve hard brakes in a minute from twelve in five
 * @param reason              which rule fired, in plain language
 * @param raisedAt            when the alert was produced, epoch millis
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskAlert(
        String vehicleId,
        String driverId,
        RiskTier riskTier,
        long windowStart,
        long windowEnd,
        double riskScore,
        long hardBrakeCount,
        long speedViolationCount,
        long incidentCount,
        long readingCount,
        String reason,
        long raisedAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise alert for " + vehicleId, e);
        }
    }

    public static RiskAlert fromJson(String json) {
        try {
            return MAPPER.readValue(json, RiskAlert.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed alert payload", e);
        }
    }
}
