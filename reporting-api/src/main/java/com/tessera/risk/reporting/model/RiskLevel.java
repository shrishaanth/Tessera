package com.tessera.risk.reporting.model;

/**
 * Risk banded for display.
 *
 * <p>The score is a continuous 0-100, but a map marker has to be one colour. Bands
 * are defined here, in the service, rather than in the dashboard: two clients
 * choosing their own cut-points would disagree about which vehicles are "high
 * risk", and an operator comparing a map against a list would be looking at two
 * different answers to the same question.
 *
 * <p>The boundaries follow the measured per-tier scores — safe driving averages
 * around 24, average around 42, risky around 82 — so a band boundary means
 * something about the fleet rather than being a round number.
 */
public enum RiskLevel {
    /** Nothing of note. Most windows for most drivers. */
    LOW,
    /** Elevated, worth watching, not worth acting on. */
    MODERATE,
    /** Sustained unsafe behaviour, or an incident. Worth a look now. */
    HIGH,
    /** At or near the top of the scale. */
    SEVERE;

    public static RiskLevel of(double riskScore) {
        if (riskScore >= 85.0) {
            return SEVERE;
        }
        if (riskScore >= 60.0) {
            return HIGH;
        }
        if (riskScore >= 30.0) {
            return MODERATE;
        }
        return LOW;
    }
}
