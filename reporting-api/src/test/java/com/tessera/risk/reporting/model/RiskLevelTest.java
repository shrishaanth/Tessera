package com.tessera.risk.reporting.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the display bands.
 *
 * <p>Banding lives in the service so that every client agrees on which vehicles are
 * "high risk". These assertions pin the boundaries against the measured per-tier
 * scores, so a band cannot quietly stop meaning anything about the fleet.
 */
class RiskLevelTest {

    @Test
    @DisplayName("the bands separate the measured driver tiers")
    void bandsSeparateTheTiers() {
        // Measured averages: safe driving near 24, average near 42, risky near 82.
        assertThat(RiskLevel.of(24.0)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.of(42.0)).isEqualTo(RiskLevel.MODERATE);
        assertThat(RiskLevel.of(82.0)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("boundaries are inclusive at the bottom of each band")
    void boundariesAreInclusive() {
        assertThat(RiskLevel.of(29.99)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.of(30.0)).isEqualTo(RiskLevel.MODERATE);
        assertThat(RiskLevel.of(59.99)).isEqualTo(RiskLevel.MODERATE);
        assertThat(RiskLevel.of(60.0)).isEqualTo(RiskLevel.HIGH);
        assertThat(RiskLevel.of(84.99)).isEqualTo(RiskLevel.HIGH);
        assertThat(RiskLevel.of(85.0)).isEqualTo(RiskLevel.SEVERE);
    }

    @Test
    @DisplayName("a calm window is LOW and a saturated one SEVERE")
    void extremesLandWhereExpected() {
        assertThat(RiskLevel.of(0.0)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.of(100.0)).isEqualTo(RiskLevel.SEVERE);
    }
}
