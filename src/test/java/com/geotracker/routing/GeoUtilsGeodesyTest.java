package com.geotracker.routing;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeoUtilsGeodesyTest {

    @Test
    void bearingCardinalDirections() {
        assertEquals(0, GeoUtils.bearingDegrees(47.648, -122.333, 47.649, -122.333), 1e-6);
        assertEquals(180, GeoUtils.bearingDegrees(47.649, -122.333, 47.648, -122.333), 1e-6);
        assertEquals(90, GeoUtils.bearingDegrees(47.648, -122.333, 47.648, -122.332), 0.01);
        assertEquals(270, GeoUtils.bearingDegrees(47.648, -122.332, 47.648, -122.333), 0.01);
    }

    @Test
    void bearingAccountsForLongitudeConvergence() {
        // Equal degree offsets in lat and lng are NOT 45 degrees at 47.6 N: a degree of longitude is
        // only cos(47.6) as long. atan2(dLng, dLat) (the old formula) would report 45.
        double b = GeoUtils.bearingDegrees(47.648, -122.333, 47.649, -122.332);
        assertEquals(Math.toDegrees(Math.atan2(Math.cos(Math.toRadians(47.6485)), 1)), b, 0.05);
        assertTrue(b < 40, "Expected about 34 degrees, got " + b);
    }

    @Test
    void cardinalLabels() {
        assertEquals("N", GeoUtils.cardinal(0));
        assertEquals("N", GeoUtils.cardinal(359));
        assertEquals("E", GeoUtils.cardinal(90));
        assertEquals("SW", GeoUtils.cardinal(225));
        assertEquals("", GeoUtils.cardinal(-1));
    }

    @Test
    void radiusBoundingBoxContainsTheWholeCircle() {
        double lat = 47.648, lng = -122.333;
        for (double r : new double[]{1, 50, 150, 1_000, 20_000}) {
            BoundingBox box = GeoUtils.radiusBoundingBox(lng, lat, r);
            // Walk along the circle and check every point is inside the box.
            for (int deg = 0; deg < 360; deg += 2) {
                double[] p = destination(lat, lng, deg, r * 0.999999);
                assertTrue(box.contains(p[1], p[0]), "r=" + r + " bearing=" + deg + " escaped the box");
            }
        }
    }

    @Test
    void polygonAreaOfKnownRectangle() {
        // 0.001 deg x 0.001 deg at 47.648 N is about 75.0 m x 111.2 m.
        List<Position> square = List.of(
                new Position(-122.334, 47.648, 0), new Position(-122.333, 47.648, 0),
                new Position(-122.333, 47.649, 0), new Position(-122.334, 47.649, 0));
        double expected = (0.001 * 111_194.9 * Math.cos(Math.toRadians(47.6485))) * (0.001 * 111_194.9);
        assertEquals(expected, GeoUtils.polygonAreaSqMeters(square), expected * 0.001);
    }

    /** Great-circle destination point (same sphere as GeoUtils). */
    private static double[] destination(double lat, double lng, double bearingDeg, double meters) {
        double R = 6_371_000.0;
        double d = meters / R, brg = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(lat), lam1 = Math.toRadians(lng);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(d) + Math.cos(phi1) * Math.sin(d) * Math.cos(brg));
        double lam2 = lam1 + Math.atan2(Math.sin(brg) * Math.sin(d) * Math.cos(phi1), Math.cos(d) - Math.sin(phi1) * Math.sin(phi2));
        return new double[]{Math.toDegrees(phi2), Math.toDegrees(lam2)};
    }
}
