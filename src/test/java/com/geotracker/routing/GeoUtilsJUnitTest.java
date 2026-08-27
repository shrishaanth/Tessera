package com.geotracker.routing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class GeoUtilsJUnitTest {

    @Test
    void haversineKnownDistanceSeattleToPortland() {
        double seattleLat = 47.6062;
        double seattleLon = -122.3321;
        double portlandLat = 45.5152;
        double portlandLon = -122.6784;

        double distance = GeoUtils.haversineMeters(seattleLat, seattleLon, portlandLat, portlandLon);

        assertTrue(distance > 230_000 && distance < 250_000,
            "Expected ~234 km, got " + distance);
    }

    @Test
    void haversineSamePointIsZero() {
        assertEquals(0.0, GeoUtils.haversineMeters(47.65, -122.33, 47.65, -122.33), 0.001);
    }

    @Test
    void haversineApproxOneKm() {
        double lat1 = 47.646;
        double lon1 = -122.334;
        double lat2 = 47.655;
        double lon2 = -122.334;

        double distance = GeoUtils.haversineMeters(lat1, lon1, lat2, lon2);
        assertTrue(distance > 900 && distance < 1100,
            "Expected ~1 km, got " + distance);
    }
}
