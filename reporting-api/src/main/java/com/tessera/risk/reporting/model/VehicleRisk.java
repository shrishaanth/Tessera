package com.tessera.risk.reporting.model;

/**
 * One vehicle's risk state for one window, as the dashboard sees it.
 *
 * <p>Flat and JSON-shaped on purpose: it crosses an HTTP boundary to a TypeScript
 * client, and a nested structure would buy nothing there.
 *
 * @param riskScore      the rule-based composite, always present
 * @param riskLevel      that score banded for display
 * @param predictedLabel the model's class, or null when no model has run
 * @param probability    the model's confidence, or null when no model has run
 * @param modelScored    whether a model contributed, so a client can say "not yet
 *                       scored" instead of showing an absent prediction as a zero
 */
public record VehicleRisk(
        String vehicleId,
        String driverId,
        String driverName,
        String riskTier,
        long windowStart,
        long windowEnd,
        long readingCount,
        long hardBrakeCount,
        long speedViolationCount,
        long incidentCount,
        double avgSpeedKph,
        double maxSpeedKph,
        double avgOverLimitRatio,
        double maxSeverity,
        double movingRatio,
        double lat,
        double lon,
        double headingDeg,
        long lastReadingTs,
        double riskScore,
        RiskLevel riskLevel,
        Long predictedLabel,
        Double probability,
        boolean modelScored) { }
