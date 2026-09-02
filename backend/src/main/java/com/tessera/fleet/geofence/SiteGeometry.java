package com.tessera.fleet.geofence;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;

import com.tessera.fleet.routing.GeoMath;

/**
 * The boundary of one customer site (FR-3.1), held in a form the live geofence
 * engine can test containment against quickly.
 *
 * <p>Coordinates are stored lon/lat (x/y), SRID 4326, to match PostGIS. A
 * radius-defined site is materialised as a 64-point metric circle so containment
 * uses the same fast point-in-polygon path as a hand-drawn polygon.
 */
public final class SiteGeometry {

    private static final GeometryFactory GF =
            new GeometryFactory(new PrecisionModel(), 4326);
    private static final int CIRCLE_SEGMENTS = 64;

    private final Geometry geometry;
    private final PreparedGeometry prepared;

    private SiteGeometry(Geometry geometry) {
        this.geometry = geometry;
        this.prepared = PreparedGeometryFactory.prepare(geometry);
    }

    /** @param ring closed or open list of {@code [lat, lon]} vertices, at least 3. */
    public static SiteGeometry fromPolygon(List<double[]> ring) {
        if (ring == null || ring.size() < 3) {
            throw new IllegalArgumentException("A polygon site needs at least 3 vertices");
        }
        List<Coordinate> coords = new ArrayList<>(ring.size() + 1);
        for (double[] p : ring) {
            coords.add(new Coordinate(p[1], p[0])); // lon, lat
        }
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (!first.equals2D(last)) {
            coords.add(new Coordinate(first));
        }
        LinearRing shell = GF.createLinearRing(coords.toArray(new Coordinate[0]));
        return new SiteGeometry(GF.createPolygon(shell));
    }

    public static SiteGeometry fromRadius(double centerLat, double centerLon, double radiusMeters) {
        if (radiusMeters <= 0) {
            throw new IllegalArgumentException("radiusMeters must be positive");
        }
        Coordinate[] coords = new Coordinate[CIRCLE_SEGMENTS + 1];
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double bearing = 360.0 * i / CIRCLE_SEGMENTS;
            double[] pt = GeoMath.destinationPoint(centerLat, centerLon, bearing, radiusMeters);
            coords[i] = new Coordinate(pt[1], pt[0]); // lon, lat
        }
        coords[CIRCLE_SEGMENTS] = new Coordinate(coords[0]);
        return new SiteGeometry(GF.createPolygon(GF.createLinearRing(coords)));
    }

    public static SiteGeometry fromWkt(String wkt) {
        try {
            return new SiteGeometry(new WKTReader(GF).read(wkt));
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid boundary WKT: " + e.getMessage(), e);
        }
    }

    /** {@code true} if the point is inside or on the boundary. */
    public boolean contains(double lat, double lon) {
        Point p = GF.createPoint(new Coordinate(lon, lat));
        return prepared.covers(p);
    }

    public String toWkt() {
        return new WKTWriter().write(geometry);
    }

    /** Boundary vertices as {@code [lat, lon]} pairs, for rendering on the map. */
    public List<double[]> outlineLatLon() {
        Coordinate[] cs = geometry.getCoordinates();
        List<double[]> out = new ArrayList<>(cs.length);
        for (Coordinate c : cs) {
            out.add(new double[] {c.y, c.x});
        }
        return out;
    }
}
