package com.tessera.risk.reporting.model;

/**
 * One road segment's traffic and risk for one window.
 *
 * <p>Answers a different question from {@link VehicleRisk} — "is this stretch of
 * road provoking unsafe behaviour?" rather than "is this driver behaving unsafely?"
 * — which is why a junction that repeatedly produces hard braking shows up here
 * even when no individual driver looks bad.
 */
public record SegmentRisk(
        String segmentId,
        long windowStart,
        long windowEnd,
        long readingCount,
        long vehicleCount,
        double avgSpeedKph,
        double speedLimitKph,
        double complianceRatio,
        long hardBrakeCount,
        long speedViolationCount,
        long incidentCount,
        double riskIndex,
        RiskLevel riskLevel) { }
