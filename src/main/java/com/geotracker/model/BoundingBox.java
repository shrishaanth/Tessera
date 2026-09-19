package com.geotracker.model;

public record BoundingBox(double minX, double minY, double maxX, double maxY) {
    public boolean contains(double x, double y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    public boolean intersects(BoundingBox other) {
        return !(other.maxX < minX || other.minX > maxX || other.maxY < minY || other.minY > maxY);
    }
}
