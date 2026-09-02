package com.tessera.fleet.routing;

/**
 * An immutable, directed road-network graph in compressed-sparse-row (CSR) form,
 * with both a forward and a reverse edge index.
 *
 * <p>Built from real OpenStreetMap data (see {@code infra/scripts/build_roadgraph.py}).
 * Node ids are dense 0..n-1 internal indices; the original OSM ids are kept only
 * for traceability. Edge weight is travel time in seconds, derived from segment
 * length and the road's speed limit (or a per-class default) — this is what makes
 * nearest-vehicle ranking travel-time-aware rather than straight-line (FR-2.2).
 *
 * <p>The reverse index lets a single Dijkstra rooted at a job's location yield the
 * drive time <em>into</em> that job from every node, so each candidate vehicle's
 * ETA is then an O(1) lookup.
 */
public final class RoadGraph {

    private final String areaName;
    private final int nodeCount;
    private final double[] lat;
    private final double[] lon;
    private final long[] osmId;

    // Forward CSR: out-edges of node i are [fwdRowStart[i], fwdRowStart[i+1]).
    private final int[] fwdRowStart;
    private final int[] fwdTarget;
    private final double[] fwdTravelSec;

    // Reverse CSR: in-edges of node i are [revRowStart[i], revRowStart[i+1]);
    // revSource[k] is the tail of that edge, revTravelSec[k] its weight.
    private final int[] revRowStart;
    private final int[] revSource;
    private final double[] revTravelSec;

    RoadGraph(String areaName, double[] lat, double[] lon, long[] osmId,
              int[] fwdRowStart, int[] fwdTarget, double[] fwdTravelSec,
              int[] revRowStart, int[] revSource, double[] revTravelSec) {
        this.areaName = areaName;
        this.nodeCount = lat.length;
        this.lat = lat;
        this.lon = lon;
        this.osmId = osmId;
        this.fwdRowStart = fwdRowStart;
        this.fwdTarget = fwdTarget;
        this.fwdTravelSec = fwdTravelSec;
        this.revRowStart = revRowStart;
        this.revSource = revSource;
        this.revTravelSec = revTravelSec;
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

    public int fwdRangeStart(int node) {
        return fwdRowStart[node];
    }

    public int fwdRangeEnd(int node) {
        return fwdRowStart[node + 1];
    }

    public int fwdTarget(int edgeIndex) {
        return fwdTarget[edgeIndex];
    }

    public double fwdTravelSec(int edgeIndex) {
        return fwdTravelSec[edgeIndex];
    }

    public int revRangeStart(int node) {
        return revRowStart[node];
    }

    public int revRangeEnd(int node) {
        return revRowStart[node + 1];
    }

    public int revSource(int edgeIndex) {
        return revSource[edgeIndex];
    }

    public double revTravelSec(int edgeIndex) {
        return revTravelSec[edgeIndex];
    }

    /**
     * Index of the graph node closest (great-circle) to the given point.
     * Linear scan; at a few thousand nodes this is well under a millisecond and
     * keeps Phase 1 free of an extra spatial-index dependency.
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
