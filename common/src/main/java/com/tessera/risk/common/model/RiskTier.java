package com.tessera.risk.common.model;

/**
 * A driver's standing behavioural profile.
 *
 * <p>This scales how often a driver produces precursor events, and is supplied to
 * the model as an <em>input feature</em> — never as the training label. A fleet
 * operator genuinely knows a driver's historical safety record, so using it as a
 * signal is realistic; using it as the label would make the prediction task a
 * lookup on vehicle identity and teach the model nothing.
 */
public enum RiskTier {
    SAFE(0.15),
    AVERAGE(0.45),
    RISKY(1.0);

    private final double eventPropensity;

    RiskTier(double eventPropensity) {
        this.eventPropensity = eventPropensity;
    }

    /** Relative likelihood of producing precursor events, in {@code [0,1]}. */
    public double eventPropensity() {
        return eventPropensity;
    }
}
