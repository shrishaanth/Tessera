package com.geotracker.model;

public record ZoneEvent(long vehicleId, String zoneId, EventType type, long timestamp) {
    public enum EventType { ENTER, EXIT }
}
