package com.geotracker.simulator;

import com.geotracker.routing.RoadGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class VehicleSpawnHelper {
    private VehicleSpawnHelper() {}

    public static long[] pickStartAndNext(RoadGraph graph, List<Long> component, Random random) {
        long startId = component.get(random.nextInt(component.size()));
        List<RoadGraph.Edge> edges = graph.getEdges(startId);
        List<Long> validNexts = new ArrayList<>();
        for (RoadGraph.Edge e : edges) {
            if (component.contains(e.toId())) {
                validNexts.add(e.toId());
            }
        }
        long nextId;
        if (validNexts.isEmpty()) {
            nextId = startId;
        } else {
            nextId = validNexts.get(random.nextInt(validNexts.size()));
        }
        return new long[]{startId, nextId};
    }
}
