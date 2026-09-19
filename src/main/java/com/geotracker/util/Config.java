package com.geotracker.util;

public class Config {
    public static final int PORT = 9090;
    public static final int WEB_PORT = 8081;
    public static final int SHARDS = 4;
    public static final int RING_BUFFER_SIZE = 65536;
    public static final int QUADTREE_LEAF_CAPACITY = 32;
    public static final int PUBLISH_MAX_DIRTY = 50;
    public static final long PUBLISH_INTERVAL_MS = 20;
    public static final int VEHICLE_COUNT = 1000;
    public static final int DEMO_VEHICLE_COUNT = 200;
    public static final double UPDATE_RATE_HZ = 5.0;
    public static final long SEED = 42L;
    public static final double MAP_MIN_X = 0.0;
    public static final double MAP_MAX_X = 1000.0;
    public static final double MAP_MIN_Y = 0.0;
    public static final double MAP_MAX_Y = 1000.0;

    public static final double AREA_MIN_LAT = 47.646;
    public static final double AREA_MAX_LAT = 47.650;
    public static final double AREA_MIN_LNG = -122.334;
    public static final double AREA_MAX_LNG = -122.330;
    public static final String AREA_NAME = "Gas Works Park, Seattle, WA";
    public static final String ROADGRAPH_RESOURCE = "/roadgraph/osm-roadgraph.json";
    public static final String GRID_ROADGRAPH_RESOURCE = "/roadgraph/grid_roadgraph.json";
    public static final String TRAJECTORY_RESOURCE = "/data/vehicle_trajectories.csv";
}
