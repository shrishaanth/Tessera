package com.geotracker.fx;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.service.ApplicationService;

import java.util.Collection;
import java.util.List;

/**
 * Builds the JSON payloads passed to the Leaflet page (tessera-map.js).
 *
 * The backend stores positions as x = longitude, y = latitude. Leaflet expects [latitude, longitude].
 * Every conversion between the two happens here, so the ordering is defined (and tested) in one place.
 */
public final class MapPayloads {

    public static final int STATUS_ACTIVE = 0;
    public static final int STATUS_STALE = 1;
    public static final int STATUS_PAUSED = 2;
    public static final int STATUS_IDLE = 3;

    private MapPayloads() {}

    /** {"v":[id,lat,lng,heading,status,source, ...]} with heading -1 when unknown and source 1 = CSV replay. */
    public static String vehicles(List<ApplicationService.VehicleInfo> vehicles) {
        StringBuilder sb = new StringBuilder(32 + vehicles.size() * 40).append("{\"v\":[");
        boolean first = true;
        for (ApplicationService.VehicleInfo v : vehicles) {
            if (!first) sb.append(',');
            first = false;
            sb.append(v.id()).append(',');
            coord(sb, v.y()).append(',');
            coord(sb, v.x()).append(',');
            sb.append(v.headingDeg() < 0 ? -1 : Math.round(v.headingDeg())).append(',');
            sb.append(statusCode(v.status())).append(',');
            sb.append(ApplicationService.SOURCE_CSV.equals(v.source()) ? 1 : 0);
        }
        return sb.append("]}").toString();
    }

    public static int statusCode(String status) {
        return switch (status) {
            case "Stale" -> STATUS_STALE;
            case "Paused" -> STATUS_PAUSED;
            case "Idle" -> STATUS_IDLE;
            default -> STATUS_ACTIVE;
        };
    }

    /** Road segments {lng1,lat1,lng2,lat2} as [[[lat,lng],[lat,lng]], ...]. */
    public static String roadNetwork(List<double[]> segments) {
        StringBuilder sb = new StringBuilder(segments.size() * 60).append('[');
        for (int i = 0; i < segments.size(); i++) {
            double[] s = segments.get(i);
            if (i > 0) sb.append(',');
            sb.append("[[");
            coord(sb, s[1]).append(',');
            coord(sb, s[0]).append("],[");
            coord(sb, s[3]).append(',');
            coord(sb, s[2]).append("]]");
        }
        return sb.append(']').toString();
    }

    /** Polylines {lng0,lat0,lng1,lat1,...} as [[[lat,lng],[lat,lng],...], ...]. */
    public static String roadPolylines(List<double[]> lines) {
        StringBuilder sb = new StringBuilder(lines.size() * 80).append('[');
        for (int i = 0; i < lines.size(); i++) {
            double[] l = lines.get(i);
            if (i > 0) sb.append(',');
            sb.append('[');
            for (int k = 0; k + 1 < l.length; k += 2) {
                if (k > 0) sb.append(',');
                latLng(sb, l[k + 1], l[k]);
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    /** Bounds as [south, west, north, east]. */
    public static String bounds(BoundingBox b) {
        StringBuilder sb = new StringBuilder("[");
        coord(sb, b.minY()).append(',');
        coord(sb, b.minX()).append(',');
        coord(sb, b.maxY()).append(',');
        coord(sb, b.maxX());
        return sb.append(']').toString();
    }

    /** Positions (x = lng, y = lat) as [[lat,lng], ...]. */
    public static String latLngs(Collection<Position> positions) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Position p : positions) {
            if (!first) sb.append(',');
            first = false;
            latLng(sb, p.y(), p.x());
        }
        return sb.append(']').toString();
    }

    /** Path as [[lat,lng], ...] from {lng, lat} pairs. */
    public static String pathFromLngLat(List<double[]> lngLat) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < lngLat.size(); i++) {
            if (i > 0) sb.append(',');
            latLng(sb, lngLat.get(i)[1], lngLat.get(i)[0]);
        }
        return sb.append(']').toString();
    }

    public static StringBuilder latLng(StringBuilder sb, double lat, double lng) {
        sb.append('[');
        coord(sb, lat).append(',');
        coord(sb, lng);
        return sb.append(']');
    }

    public static String ids(Collection<Long> ids) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (long id : ids) {
            if (!first) sb.append(',');
            first = false;
            sb.append(id);
        }
        return sb.append(']').toString();
    }

    /** JSON string literal with quotes. */
    public static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ' || c == '<') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Wraps a JSON document as a single-quoted JavaScript string literal for executeScript, so the page
     * parses it with JSON.parse rather than evaluating it as code.
     */
    public static String jsArgument(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 2).append('\'');
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            switch (c) {
                case '\'' -> sb.append("\\'");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case ' ' -> sb.append("\\u2028");
                case ' ' -> sb.append("\\u2029");
                default -> sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    /** Appends v with 7 decimals (about 1 cm) in fixed notation: no exponent, no locale separators. */
    static StringBuilder coord(StringBuilder sb, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return sb.append('0');
        long scaled = Math.round(Math.abs(v) * 1e7);
        if (v < 0 && scaled != 0) sb.append('-');
        sb.append(scaled / 10_000_000L).append('.');
        String frac = Long.toString(scaled % 10_000_000L);
        for (int i = frac.length(); i < 7; i++) sb.append('0');
        return sb.append(frac);
    }
}
