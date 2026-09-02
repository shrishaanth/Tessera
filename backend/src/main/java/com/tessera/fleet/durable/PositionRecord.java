package com.tessera.fleet.durable;

/**
 * A position fix as it is stored durably (SRS §7 "Position (durable)"). Every row
 * is unambiguously attributable to a vehicle and a timestamp (NFR-5).
 */
public record PositionRecord(
        String vehicleId,
        double latitude,
        double longitude,
        double speedKph,
        double headingDeg,
        long epochMillis) {
}
