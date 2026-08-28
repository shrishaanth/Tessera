package com.geotracker.model;

import java.util.List;

public record VehicleDetail(
        long vehicleId,
        Position position,
        double speedKmh,
        double heading,
        List<String> zones,
        long lastUpdate,
        String status
) {}
