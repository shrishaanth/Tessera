package com.tessera.fleet.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeoMathTest {

    @Test
    void haversineIsZeroForIdenticalPoints() {
        assertThat(GeoMath.haversineMeters(42.36, -71.06, 42.36, -71.06)).isZero();
    }

    @Test
    void haversineOneDegreeOfLatitudeIsAboutOneEleventhOfEarthQuadrant() {
        double m = GeoMath.haversineMeters(0, 0, 1, 0);
        // ~111.19 km per degree of latitude on the authalic sphere.
        assertThat(m).isBetween(111_000.0, 111_400.0);
    }

    @Test
    void bearingDueEastIsNinetyDegrees() {
        double b = GeoMath.bearingDegrees(0, 0, 0, 1);
        assertThat(b).isBetween(89.9, 90.1);
    }

    @Test
    void bearingDueNorthIsZeroDegrees() {
        double b = GeoMath.bearingDegrees(0, 0, 1, 0);
        assertThat(b % 360).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.1));
    }
}
