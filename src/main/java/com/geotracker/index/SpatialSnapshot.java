package com.geotracker.index;

public class SpatialSnapshot {
    private final CowQuadtree quadtree;
    private final HamtIndex hamt;

    public SpatialSnapshot(CowQuadtree quadtree, HamtIndex hamt) {
        this.quadtree = quadtree;
        this.hamt = hamt;
    }

    public CowQuadtree quadtree() {
        return quadtree;
    }

    public HamtIndex hamt() {
        return hamt;
    }
}
