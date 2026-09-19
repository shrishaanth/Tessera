package com.geotracker.model;

import java.util.List;

public record RouteResult(List<Long> nodeIds, double totalCost, long vehicleId) {
}
