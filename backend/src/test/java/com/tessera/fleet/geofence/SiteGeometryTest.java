package com.tessera.fleet.geofence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class SiteGeometryTest {

    private static final List<double[]> SQUARE = List.of(
            new double[] {42.3550, -71.0650},
            new double[] {42.3550, -71.0620},
            new double[] {42.3570, -71.0620},
            new double[] {42.3570, -71.0650});

    @Test
    void polygonContainsInteriorPointsAndRejectsExterior() {
        SiteGeometry g = SiteGeometry.fromPolygon(SQUARE);
        assertThat(g.contains(42.3560, -71.0635)).isTrue();   // middle
        assertThat(g.contains(42.3600, -71.0635)).isFalse();  // north of it
        assertThat(g.contains(42.3560, -71.0700)).isFalse();  // west of it
    }

    @Test
    void radiusContainsCentreAndNearPointsButNotFarOnes() {
        SiteGeometry g = SiteGeometry.fromRadius(42.3560, -71.0635, 150);
        assertThat(g.contains(42.3560, -71.0635)).isTrue();                 // centre
        assertThat(g.contains(42.3567, -71.0635)).isTrue();  // ~78 m north
        assertThat(g.contains(42.3620, -71.0635)).isFalse(); // ~670 m north
    }

    @Test
    void wktRoundTripsThroughFromWkt() {
        SiteGeometry original = SiteGeometry.fromRadius(42.356, -71.0635, 120);
        String wkt = original.toWkt();
        assertThat(wkt).startsWith("POLYGON");
        SiteGeometry reparsed = SiteGeometry.fromWkt(wkt);
        assertThat(reparsed.contains(42.356, -71.0635)).isTrue();
        assertThat(reparsed.contains(42.370, -71.0635)).isFalse();
    }

    @Test
    void wktIsLonLatOrder() {
        String wkt = SiteGeometry.fromPolygon(SQUARE).toWkt();
        // First ordinate is longitude (~ -71), second is latitude (~ 42).
        assertThat(wkt).contains("-71.06");
    }

    @Test
    void rejectsDegeneratePolygonAndNonPositiveRadius() {
        assertThatThrownBy(() -> SiteGeometry.fromPolygon(List.of(new double[] {1, 1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteGeometry.fromRadius(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteGeometry.fromWkt("not wkt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
