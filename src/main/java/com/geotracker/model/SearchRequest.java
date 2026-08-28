package com.geotracker.model;

import java.util.List;
import java.util.Set;

public record SearchRequest(
        BoundingBox bbox,
        Set<Long> vehicleIds
) {}
