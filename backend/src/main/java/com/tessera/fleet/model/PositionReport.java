package com.tessera.fleet.model;

/**
 * A single position report emitted by a {@link com.tessera.fleet.ingestion.PositionSource}.
 * Immutable; every durably stored position must be unambiguously attributable to a
 * vehicle and a timestamp (NFR-5), so both are required here.
 *
 * @param vehicleId    stable identifier of the reporting vehicle
 * @param driverName   driver display name, or {@code null} if the feed does not carry one
 * @param latitude     WGS-84 latitude, degrees
 * @param longitude    WGS-84 longitude, degrees
 * @param headingDeg   course over ground in degrees (0–360), or {@code NaN} if unknown
 * @param speedKph     ground speed in km/h, or {@code NaN} if unknown
 * @param epochMillis  time the position was measured, epoch milliseconds UTC
 */
public record PositionReport(
        String vehicleId,
        String driverName,
        double latitude,
        double longitude,
        double headingDeg,
        double speedKph,
        long epochMillis) {

    public PositionReport {
        if (vehicleId == null || vehicleId.isBlank()) {
            throw new IllegalArgumentException("vehicleId is required");
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException(
                    "position out of range: " + latitude + "," + longitude);
        }
        if (epochMillis <= 0) {
            throw new IllegalArgumentException("epochMillis is required");
        }
    }
}
