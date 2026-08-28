package com.geotracker.model;

import java.util.List;
import java.util.Set;

public record ZoneRequest(
        String name,
        List<Position> polygon,
        Set<Long> vehicleIds,
        boolean alertOnEnter,
        boolean alertOnExit
) {}
