package com.geotracker.dataset;

import com.geotracker.util.Config;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DatasetValidationTest {

    @Test
    void trajectoryFileExists() {
        assertNotNull(getClass().getResourceAsStream(Config.TRAJECTORY_RESOURCE),
                "Trajectory CSV resource should exist");
    }

    @Test
    void trajectoryHeaderIsValid() throws IOException {
        try (var is = getClass().getResourceAsStream(Config.TRAJECTORY_RESOURCE);
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            assertEquals("vehicle_id,timestamp_ms,latitude,longitude,speed_kmh", header);
        }
    }

    @Test
    void trajectoryHas20Vehicles() throws IOException {
        Set<String> vehicles = new HashSet<>();
        try (var is = getClass().getResourceAsStream(Config.TRAJECTORY_RESOURCE);
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                vehicles.add(line.split(",")[0]);
            }
        }
        assertEquals(20, vehicles.size(), "Expected 20 unique vehicle IDs");
    }

    @Test
    void trajectoryCoordinatesAreInSeattleArea() throws IOException {
        try (var is = getClass().getResourceAsStream(Config.TRAJECTORY_RESOURCE);
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            reader.readLine(); // skip header
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                double lat = Double.parseDouble(parts[2]);
                double lng = Double.parseDouble(parts[3]);
                assertTrue(lat >= 47.64 && lat <= 47.66,
                        "Latitude out of range: " + lat + " at line " + (count + 2));
                assertTrue(lng >= -122.34 && lng <= -122.32,
                        "Longitude out of range: " + lng + " at line " + (count + 2));
                count++;
            }
            assertTrue(count > 2000, "Expected >2000 records, got " + count);
        }
    }

    @Test
    void trajectoryTimestampsAreMonotonicallyIncreasingPerVehicle() throws IOException {
        Map<String, Long> lastTimestamp = new HashMap<>();
        try (var is = getClass().getResourceAsStream(Config.TRAJECTORY_RESOURCE);
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            reader.readLine(); // skip header
            String line;
            int lineNum = 2;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                String vid = parts[0];
                long ts = Long.parseLong(parts[1]);
                Long prev = lastTimestamp.get(vid);
                if (prev != null) {
                    assertTrue(ts >= prev,
                            "Timestamp not monotonic for vehicle " + vid + " at line " + lineNum);
                }
                lastTimestamp.put(vid, ts);
                lineNum++;
            }
        }
    }

    @Test
    void trajectorySpeedsAreNonNegative() throws IOException {
        try (var is = getClass().getResourceAsStream(Config.TRAJECTORY_RESOURCE);
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                double speed = Double.parseDouble(parts[4]);
                assertTrue(speed >= 0, "Negative speed: " + speed);
                assertTrue(speed < 200, "Implausible speed: " + speed + " km/h");
            }
        }
    }

    @Test
    void trajectoryNoDuplicateRecords() throws IOException {
        Set<String> seen = new HashSet<>();
        try (var is = getClass().getResourceAsStream(Config.TRAJECTORY_RESOURCE);
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                String key = parts[0] + "," + parts[1];
                assertTrue(seen.add(key), "Duplicate record: " + key);
            }
        }
    }

    @Test
    void osmRoadGraphExists() {
        assertNotNull(getClass().getResourceAsStream(Config.ROADGRAPH_RESOURCE),
                "OSM road graph resource should exist");
    }

    @Test
    void gridRoadGraphExists() {
        assertNotNull(getClass().getResourceAsStream(Config.GRID_ROADGRAPH_RESOURCE),
                "Grid road graph resource should exist");
    }

    @Test
    void osmRoadGraphParsesCorrectly() throws Exception {
        try (var is = getClass().getResourceAsStream(Config.ROADGRAPH_RESOURCE)) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var graph = com.geotracker.routing.RoadGraph.loadFromJson(json);
            assertNotNull(graph);
            assertTrue(graph.getAllNodes().size() > 10, "Expected >10 nodes in OSM graph");
        }
    }

    @Test
    void gridRoadGraphParsesCorrectly() throws Exception {
        try (var is = getClass().getResourceAsStream(Config.GRID_ROADGRAPH_RESOURCE)) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var graph = com.geotracker.routing.RoadGraph.loadFromJson(json);
            assertNotNull(graph);
            assertTrue(graph.getAllNodes().size() > 10, "Expected >10 nodes in grid graph");
        }
    }

    @Test
    void vehicleTripsFileExists() {
        assertNotNull(getClass().getResourceAsStream("/data/vehicle_trips.json"),
                "Vehicle trips JSON should exist");
    }
}
