package com.geotracker.routing;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;

import java.util.List;

public final class GeoUtils {
    private static final double EARTH_RADIUS_M = 6371000.0;
    private static final String[] CARDINALS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private GeoUtils() {}

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    /** Initial great-circle bearing from point 1 to point 2, in degrees clockwise from north [0, 360). */
    public static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    /** Eight-point compass label for a bearing in degrees, or "" when the bearing is unknown (negative). */
    public static String cardinal(double bearingDeg) {
        if (bearingDeg < 0 || Double.isNaN(bearingDeg)) return "";
        int index = (int) Math.round(bearingDeg / 45.0) % 8;
        return CARDINALS[index];
    }

    /**
     * Smallest lng/lat box that contains every point within {@code radiusMeters} (haversine distance on
     * the same sphere as {@link #haversineMeters}) of the centre. Used as the index pre-filter for radius
     * queries, so it must never be smaller than the circle.
     */
    public static BoundingBox radiusBoundingBox(double centerLng, double centerLat, double radiusMeters) {
        double angular = radiusMeters / EARTH_RADIUS_M;
        double latDeg = Math.toDegrees(angular);
        double cosLat = Math.cos(Math.toRadians(centerLat));
        double lngDeg;
        double sinAngular = Math.sin(angular);
        if (sinAngular >= cosLat) {
            lngDeg = 180.0;
        } else {
            lngDeg = Math.toDegrees(Math.asin(sinAngular / cosLat));
        }
        double eps = 1e-9;
        return new BoundingBox(centerLng - lngDeg - eps, centerLat - latDeg - eps,
                centerLng + lngDeg + eps, centerLat + latDeg + eps);
    }

    /** Approximate area of a small lng/lat polygon in square metres (equirectangular projection at its centroid). */
    public static double polygonAreaSqMeters(List<Position> polygon) {
        if (polygon == null || polygon.size() < 3) return 0.0;
        double meanLat = 0;
        for (Position p : polygon) meanLat += p.y();
        meanLat /= polygon.size();
        double mPerDegLat = Math.toRadians(1) * EARTH_RADIUS_M;
        double mPerDegLng = mPerDegLat * Math.cos(Math.toRadians(meanLat));
        double sum = 0;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).x() * mPerDegLng, yi = polygon.get(i).y() * mPerDegLat;
            double xj = polygon.get(j).x() * mPerDegLng, yj = polygon.get(j).y() * mPerDegLat;
            sum += (xj * yi) - (xi * yj);
        }
        return Math.abs(sum) / 2.0;
    }
}
