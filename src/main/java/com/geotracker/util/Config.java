package com.geotracker.util;

public class Config {
    public static final int PORT = 8080;
    public static final int SHARDS = 4;
    public static final int RING_BUFFER_SIZE = 65536;
    public static final int QUADTREE_LEAF_CAPACITY = 4;
    public static final int PUBLISH_MAX_DIRTY = 50;
    public static final long PUBLISH_INTERVAL_MS = 20;
    public static final int VEHICLE_COUNT = 1000;
    public static final double UPDATE_RATE_HZ = 5.0;
    public static final long SEED = 42L;
    public static final double MAP_MIN_X = 0.0;
    public static final double MAP_MAX_X = 1000.0;
    public static final double MAP_MIN_Y = 0.0;
    public static final double MAP_MAX_Y = 1000.0;
}
