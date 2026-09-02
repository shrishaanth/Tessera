package com.tessera.fleet.support;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.DefaultResourceLoader;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.routing.RoadGraph;
import com.tessera.fleet.routing.RoadGraphLoader;

/** Shared builders for tests that need config or the real road graph. */
public final class TestFixtures {

    public static final String ROAD_GRAPH_RESOURCE = "classpath:roadgraph/roadgraph.json";

    private TestFixtures() { }

    public static FleetProperties fleetProperties() {
        return new FleetProperties(
                30,
                1000L,
                1000L,
                new FleetProperties.Nearest(2500, 12000, 5),
                FleetProperties.PositionSourceType.SIMULATOR,
                new FleetProperties.Simulator(20, 1000L, 42L, 0.7),
                new FleetProperties.Gtfs(null, null, null, 15000L, null),
                ROAD_GRAPH_RESOURCE,
                List.of(new FleetProperties.User("dispatch", "{noop}dispatch", "DISPATCHER")));
    }

    /** Loads the committed OSM road graph from the test classpath. */
    public static RoadGraph realRoadGraph() {
        return new RoadGraphLoader(new DefaultResourceLoader(), new ObjectMapper(), fleetProperties())
                .load();
    }

    /**
     * A tiny hand-built directed graph used to check routing arithmetic exactly.
     *
     * <pre>
     *   0 --10s--> 1 --10s--> 2
     *   0 --------- 30s -----> 2   (slow direct edge)
     *   2 --5s--> 3
     * </pre>
     * All edges one-way in the direction shown.
     */
    public static RoadGraph tinyGraph() {
        String json = """
            {
              "name": "tiny",
              "nodes": [
                {"id": 0, "lat": 0.0,  "lon": 0.0},
                {"id": 1, "lat": 0.0,  "lon": 0.1},
                {"id": 2, "lat": 0.0,  "lon": 0.2},
                {"id": 3, "lat": 0.05, "lon": 0.2}
              ],
              "edges": [
                {"from": 0, "to": 1, "travelSec": 10, "lengthM": 100, "speedKph": 36},
                {"from": 1, "to": 2, "travelSec": 10, "lengthM": 100, "speedKph": 36},
                {"from": 0, "to": 2, "travelSec": 30, "lengthM": 100, "speedKph": 12},
                {"from": 2, "to": 3, "travelSec": 5,  "lengthM": 50,  "speedKph": 36}
              ]
            }
            """;
        try {
            return RoadGraphLoader.parse(new ObjectMapper().readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
