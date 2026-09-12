package com.tessera.risk.common.road;

import com.tessera.risk.common.geo.GeoMath;

/**
 * An immutable directed road network in compressed-sparse-row (CSR) form, built
 * from real OpenStreetMap data.
 *
 * <p>Node ids are dense internal indices {@code 0..nodeCount-1}; the original OSM
 * ids are retained only for traceability. Each directed edge is a <em>segment</em>
 * — the unit the analytics aggregate over — and carries the real posted speed
 * limit from OSM, which is what makes speed-violation detection meaningful rather
 * than an arbitrary threshold.
 *
 * <p>Both a forward and a reverse index are kept: the forward index drives routing
 * and simulation, the reverse index is used to test whether a node can reach a
 * given destination (so a generated tour can always be closed).
 */
public final class RoadNetwork {

    private final String areaName;
    private final int nodeCount;
    private final double[] lat;
    private final double[] lon;
    private final long[] osmId;

    // Forward CSR: out-edges of node i occupy [fwdRowStart[i], fwdRowStart[i+1]).
    private final int[] fwdRowStart;
    private final int[] fwdTarget;
    private final double[] fwdLengthM;
    private final double[] fwdSpeedLimitKph;
    private final double[] fwdTravelSec;
    private final String[] fwdStreetName;

    // Reverse CSR: in-edges of node i; revSource[k] is that edge's tail.
    private final int[] revRowStart;
    private final int[] revSource;

    RoadNetwork(String areaName, double[] lat, double[] lon, long[] osmId,
                int[] fwdRowStart, int[] fwdTarget, double[] fwdLengthM,
                double[] fwdSpeedLimitKph, double[] fwdTravelSec, String[] fwdStreetName,
                int[] revRowStart, int[] revSource) {
        this.areaName = areaName;
        this.nodeCount = lat.length;
        this.lat = lat;
        this.lon = lon;
        this.osmId = osmId;
        this.fwdRowStart = fwdRowStart;
        this.fwdTarget = fwdTarget;
        this.fwdLengthM = fwdLengthM;
        this.fwdSpeedLimitKph = fwdSpeedLimitKph;
        this.fwdTravelSec = fwdTravelSec;
        this.fwdStreetName = fwdStreetName;
        this.revRowStart = revRowStart;
        this.revSource = revSource;
    }

    public String areaName() {
        return areaName;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int edgeCount() {
        return fwdTarget.length;
    }

    public double lat(int node) {
        return lat[node];
    }

    public double lon(int node) {
        return lon[node];
    }

    public long osmId(int node) {
        return osmId[node];
    }

    // ------------------------------------------------------------ forward index

    public int fwdRangeStart(int node) {
        return fwdRowStart[node];
    }

    public int fwdRangeEnd(int node) {
        return fwdRowStart[node + 1];
    }

    public int fwdTarget(int edge) {
        return fwdTarget[edge];
    }

    public double lengthM(int edge) {
        return fwdLengthM[edge];
    }

    /** Real posted speed limit for this segment, in km/h. */
    public double speedLimitKph(int edge) {
        return fwdSpeedLimitKph[edge];
    }

    public double travelSec(int edge) {
        return fwdTravelSec[edge];
    }

    /** OSM street name, or {@code null} where the source data has none. */
    public String streetName(int edge) {
        return fwdStreetName[edge];
    }

    /**
     * Stable identifier for a segment, used as the HBase row-key prefix and the
     * Kafka aggregation key. Derived from the edge index, which is deterministic
     * for a given road-graph resource.
     */
    public String segmentId(int edge) {
        return String.format("SEG-%05d", edge);
    }

    /** The directed edge from {@code from} to {@code to}, or -1 if none exists. */
    public int edgeBetween(int from, int to) {
        for (int e = fwdRowStart[from]; e < fwdRowStart[from + 1]; e++) {
            if (fwdTarget[e] == to) {
                return e;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ reverse index

    public int revRangeStart(int node) {
        return revRowStart[node];
    }

    public int revRangeEnd(int node) {
        return revRowStart[node + 1];
    }

    public int revSource(int edge) {
        return revSource[edge];
    }

    // ------------------------------------------------------------------ queries

    /**
     * Index of the graph node closest to a point. A linear scan: at a few thousand
     * nodes this is well under a millisecond and avoids a spatial-index dependency.
     */
    public int nearestNode(double queryLat, double queryLon) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < nodeCount; i++) {
            double d = GeoMath.haversineMeters(queryLat, queryLon, lat[i], lon[i]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
