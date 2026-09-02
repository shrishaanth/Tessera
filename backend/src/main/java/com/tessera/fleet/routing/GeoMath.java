package com.tessera.fleet.routing;

/** Small geodesic helpers shared by the routing and simulator code. */
public final class GeoMath {

    /** Mean Earth radius in metres (WGS-84 authalic sphere). */
    public static final double EARTH_RADIUS_M = 6_371_000.0;

    private GeoMath() { }

    /** Great-circle distance in metres between two WGS-84 points. */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * The point reached by travelling {@code distanceMeters} from
     * ({@code lat},{@code lon}) along an initial {@code bearingDeg}. Returns
     * {@code [lat, lon]} in degrees. Used to draw metric circles for
     * radius-defined geofences.
     */
    public static double[] destinationPoint(double lat, double lon,
                                            double bearingDeg, double distanceMeters) {
        double angular = distanceMeters / EARTH_RADIUS_M;
        double brng = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(lat);
        double lambda1 = Math.toRadians(lon);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(angular)
                + Math.cos(phi1) * Math.sin(angular) * Math.cos(brng));
        double lambda2 = lambda1 + Math.atan2(
                Math.sin(brng) * Math.sin(angular) * Math.cos(phi1),
                Math.cos(angular) - Math.sin(phi1) * Math.sin(phi2));
        return new double[] {Math.toDegrees(phi2), Math.toDegrees(lambda2)};
    }

    /** Initial bearing in degrees (0–360) from point 1 to point 2. */
    public static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dLambda = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLambda) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dLambda);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }
}
