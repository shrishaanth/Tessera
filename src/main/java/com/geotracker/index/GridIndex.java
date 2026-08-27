package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;

import java.util.*;

public class GridIndex implements SpatialIndex {
    private record Entry(long vehicleId, double x, double y) {}

    private final double minX, minY, maxX, maxY;
    private final int cellX, cellY;
    private final double cellWidth, cellHeight;
    @SuppressWarnings("unchecked")
    private final List<Entry>[][] grid;

    public GridIndex(BoundingBox bounds, int cellX, int cellY) {
        this.minX = bounds.minX();
        this.minY = bounds.minY();
        this.maxX = bounds.maxX();
        this.maxY = bounds.maxY();
        this.cellX = cellX;
        this.cellY = cellY;
        this.cellWidth = (maxX - minX) / cellX;
        this.cellHeight = (maxY - minY) / cellY;
        this.grid = new ArrayList[cellX][cellY];
        for (int i = 0; i < cellX; i++) {
            for (int j = 0; j < cellY; j++) {
                grid[i][j] = new ArrayList<>();
            }
        }
    }

    @Override
    public synchronized void insert(long vehicleId, double x, double y) {
        int cx = (int) ((x - minX) / cellWidth);
        int cy = (int) ((y - minY) / cellHeight);
        cx = Math.max(0, Math.min(cellX - 1, cx));
        cy = Math.max(0, Math.min(cellY - 1, cy));
        grid[cx][cy].add(new Entry(vehicleId, x, y));
    }

    @Override
    public synchronized void remove(long vehicleId, double x, double y) {
        int cx = (int) ((x - minX) / cellWidth);
        int cy = (int) ((y - minY) / cellHeight);
        cx = Math.max(0, Math.min(cellX - 1, cx));
        cy = Math.max(0, Math.min(cellY - 1, cy));
        grid[cx][cy].removeIf(e -> e.vehicleId() == vehicleId);
    }

    @Override
    public synchronized List<Long> rangeQuery(BoundingBox bbox) {
        Set<Long> result = new HashSet<>();
        int minCx = (int) ((bbox.minX() - minX) / cellWidth);
        int minCy = (int) ((bbox.minY() - minY) / cellHeight);
        int maxCx = (int) ((bbox.maxX() - minX) / cellWidth);
        int maxCy = (int) ((bbox.maxY() - minY) / cellHeight);
        minCx = Math.max(0, minCx);
        minCy = Math.max(0, minCy);
        maxCx = Math.min(cellX - 1, maxCx);
        maxCy = Math.min(cellY - 1, maxCy);
        for (int i = minCx; i <= maxCx; i++) {
            for (int j = minCy; j <= maxCy; j++) {
                for (Entry e : grid[i][j]) {
                    result.add(e.vehicleId());
                }
            }
        }
        return new ArrayList<>(result);
    }

    @Override
    public synchronized NearestResult nearest(double x, double y) {
        long bestId = -1;
        double bestX = 0;
        double bestY = 0;
        double bestDist = Double.MAX_VALUE;

        int cx = (int) ((x - minX) / cellWidth);
        int cy = (int) ((y - minY) / cellHeight);
        int maxRadius = Math.max(cellX, cellY);

        for (int radius = 0; radius < maxRadius; radius++) {
            int iMin = Math.max(0, cx - radius);
            int iMax = Math.min(cellX - 1, cx + radius);
            int jMin = Math.max(0, cy - radius);
            int jMax = Math.min(cellY - 1, cy + radius);
            for (int i = iMin; i <= iMax; i++) {
                for (int j = jMin; j <= jMax; j++) {
                    if (radius > 0 && (i > iMin && i < iMax && j > jMin && j < jMax)) continue;
                    for (Entry e : grid[i][j]) {
                        double dx = e.x() - x;
                        double dy = e.y() - y;
                        double dist = dx * dx + dy * dy;
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestId = e.vehicleId();
                            bestX = e.x();
                            bestY = e.y();
                        }
                    }
                }
            }
            if (bestId != -1) {
                return new NearestResult(bestId, bestX, bestY, Math.sqrt(bestDist));
            }
        }
        return null;
    }
}
