package com.geotracker.dashboard;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;

public class MultiShardDashboardTest {
    public static void main(String[] args) {
        boolean allPassed = true;
        allPassed &= testMultiShardVehicleAggregation();
        allPassed &= testMultiShardVehicleCount();
        allPassed &= testMultiShardCurrentPositions();
        if (allPassed) {
            System.out.println("All MultiShardDashboard tests passed");
        } else {
            System.out.println("Some MultiShardDashboard tests FAILED");
            System.exit(1);
        }
    }

    private static boolean testMultiShardVehicleAggregation() {
        try {
            BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
            CowQuadtree[] quadtrees = new CowQuadtree[2];
            HamtIndex[] hamts = new HamtIndex[2];

            for (int i = 0; i < 2; i++) {
                quadtrees[i] = new CowQuadtree(bounds);
                hamts[i] = new HamtIndex();
            }

            // Shard 0: vehicles 0, 2, 4
            quadtrees[0].insert(0, 100, 100);
            quadtrees[0].insert(2, 200, 200);
            quadtrees[0].insert(4, 300, 300);
            hamts[0].put(0, new Position(100, 100, 1000));
            hamts[0].put(2, new Position(200, 200, 2000));
            hamts[0].put(4, new Position(300, 300, 3000));
            quadtrees[0].publish();
            hamts[0].publish();

            // Shard 1: vehicles 1, 3, 5
            quadtrees[1].insert(1, 400, 400);
            quadtrees[1].insert(3, 500, 500);
            quadtrees[1].insert(5, 600, 600);
            hamts[1].put(1, new Position(400, 400, 1000));
            hamts[1].put(3, new Position(500, 500, 2000));
            hamts[1].put(5, new Position(600, 600, 3000));
            quadtrees[1].publish();
            hamts[1].publish();

            // Simulate dashboard aggregation
            SwingDashboard dashboard = new SwingDashboard(quadtrees, hamts, bounds);
            
            // Verify all vehicles are visible across shards
            int totalVehicles = 0;
            for (HamtIndex h : hamts) {
                totalVehicles += h.size();
            }
            assert totalVehicles == 6 : "Expected 6 vehicles, got " + totalVehicles;

            var allVehicles = new java.util.ArrayList<Long>();
            for (CowQuadtree qt : quadtrees) {
                allVehicles.addAll(qt.rangeQuery(bounds));
            }
            assert allVehicles.size() == 6 : "Expected 6 quadtree entries, got " + allVehicles.size();

            System.out.println("PASS: multiShardVehicleAggregation");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: multiShardVehicleAggregation - " + t.getMessage());
            return false;
        }
    }

    private static boolean testMultiShardVehicleCount() {
        try {
            BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
            CowQuadtree[] quadtrees = new CowQuadtree[2];
            HamtIndex[] hamts = new HamtIndex[2];

            for (int i = 0; i < 2; i++) {
                quadtrees[i] = new CowQuadtree(bounds);
                hamts[i] = new HamtIndex();
            }

            // Add 100 vehicles to shard 0
            for (long v = 0; v < 100; v++) {
                quadtrees[0].insert(v, v % 100, v / 100);
                hamts[0].put(v, new Position(v % 100, v / 100, 1000));
            }
            quadtrees[0].publish();
            hamts[0].publish();

            // Add 100 vehicles to shard 1
            for (long v = 100; v < 200; v++) {
                quadtrees[1].insert(v, v % 100, v / 100);
                hamts[1].put(v, new Position(v % 100, v / 100, 1000));
            }
            quadtrees[1].publish();
            hamts[1].publish();

            int totalVehicles = 0;
            for (HamtIndex h : hamts) {
                totalVehicles += h.size();
            }
            assert totalVehicles == 200 : "Expected 200 vehicles, got " + totalVehicles;

            System.out.println("PASS: multiShardVehicleCount");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: multiShardVehicleCount - " + t.getMessage());
            return false;
        }
    }

    private static boolean testMultiShardCurrentPositions() {
        try {
            BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
            CowQuadtree[] quadtrees = new CowQuadtree[2];
            HamtIndex[] hamts = new HamtIndex[2];

            for (int i = 0; i < 2; i++) {
                quadtrees[i] = new CowQuadtree(bounds);
                hamts[i] = new HamtIndex();
            }

            // Shard 0: vehicle 0 moves multiple times
            quadtrees[0].insert(0, 100, 100);
            hamts[0].put(0, new Position(100, 100, 1000));
            quadtrees[0].publish();
            hamts[0].publish();

            Position oldPos = hamts[0].get(0);
            quadtrees[0].update(0, oldPos.x(), oldPos.y(), 200, 200);
            hamts[0].put(0, new Position(200, 200, 2000));
            quadtrees[0].publish();
            hamts[0].publish();

            oldPos = hamts[0].get(0);
            quadtrees[0].update(0, oldPos.x(), oldPos.y(), 300, 300);
            hamts[0].put(0, new Position(300, 300, 3000));
            quadtrees[0].publish();
            hamts[0].publish();

            // Shard 1: vehicle 1 moves multiple times
            quadtrees[1].insert(1, 400, 400);
            hamts[1].put(1, new Position(400, 400, 1000));
            quadtrees[1].publish();
            hamts[1].publish();

            oldPos = hamts[1].get(1);
            quadtrees[1].update(1, oldPos.x(), oldPos.y(), 500, 500);
            hamts[1].put(1, new Position(500, 500, 2000));
            quadtrees[1].publish();
            hamts[1].publish();

            oldPos = hamts[1].get(1);
            quadtrees[1].update(1, oldPos.x(), oldPos.y(), 600, 600);
            hamts[1].put(1, new Position(600, 600, 3000));
            quadtrees[1].publish();
            hamts[1].publish();

            // Verify dashboard shows only current positions
            var allVehicles = new java.util.ArrayList<Long>();
            for (CowQuadtree qt : quadtrees) {
                allVehicles.addAll(qt.rangeQuery(bounds));
            }
            assert allVehicles.size() == 2 : "Expected 2 points, got " + allVehicles.size();

            int totalVehicles = 0;
            for (HamtIndex h : hamts) {
                totalVehicles += h.size();
            }
            assert totalVehicles == 2 : "Expected 2 vehicles, got " + totalVehicles;

            System.out.println("PASS: multiShardCurrentPositions");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: multiShardCurrentPositions - " + t.getMessage());
            return false;
        }
    }
}
