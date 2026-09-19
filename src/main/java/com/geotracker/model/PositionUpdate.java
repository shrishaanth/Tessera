package com.geotracker.model;

public record PositionUpdate(long vehicleId, double x, double y, long timestamp) {
}
