package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.PositionUpdate;

public class QuadtreeStalePositionTest {
    public static void main(String[] args) {
        boolean allPassed = true;
        allPassed &= testQuadtreeNoStalePositions();
        allPassed &= testMultipleVehicleMovements();
        allPassed &= testVehicleCountStable();
        if (allPassed) {
            System.out.println("All QuadtreeStalePosition tests passed");
        } else {
            System.out.println("Some QuadtreeStalePosition tests FAILED");
            System.exit(1);
        }
    }

    private static boolean testQuadtreeNoStalePositions() {
        try {
            CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 1000, 1000));
            HamtIndex hamt = new HamtIndex();

            long vehicleId = 1;

            qt.insert(vehicleId, 100, 100);
            hamt.put(vehicleId, new Position(100, 100, 1000));
            qt.publish();
            hamt.publish();

            // Move to (200, 200)
            Position oldPos = hamt.get(vehicleId);
            qt.update(vehicleId, oldPos.x(), oldPos.y(), 200, 200);
            hamt.put(vehicleId, new Position(200, 200, 2000));
            qt.publish();
            hamt.publish();

            // Move to (300, 300)
            oldPos = hamt.get(vehicleId);
            qt.update(vehicleId, oldPos.x(), oldPos.y(), 300, 300);
            hamt.put(vehicleId, new Position(300, 300, 3000));
            qt.publish();
            hamt.publish();

            var result = qt.rangeQuery(new BoundingBox(0, 0, 1000, 1000));
            assert result.size() == 1 : "Expected 1 point, got " + result.size();
            assert result.contains(vehicleId) : "Expected vehicle " + vehicleId;
            assert new Position(300, 300, 3000).equals(hamt.get(vehicleId)) : "Expected latest position";

            // Verify old positions are NOT in quadtree
            var oldPositions = qt.rangeQuery(new BoundingBox(0, 0, 150, 150));
            assert oldPositions.isEmpty() : "Old positions should not be in quadtree";

            System.out.println("PASS: quadtreeNoStalePositions");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: quadtreeNoStalePositions - " + t.getMessage());
            return false;
        }
    }

    private static boolean testMultipleVehicleMovements() {
        try {
            CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 1000, 1000));
            HamtIndex hamt = new HamtIndex();

            int vehicleCount = 50;
            for (long v = 0; v < vehicleCount; v++) {
                qt.insert(v, v * 10, v * 10);
                hamt.put(v, new Position(v * 10, v * 10, 1000));
            }
            qt.publish();
            hamt.publish();

            // Move each vehicle 10 times
            for (int move = 0; move < 10; move++) {
                for (long v = 0; v < vehicleCount; v++) {
                    Position oldPos = hamt.get(v);
                    double newX = oldPos.x() + 1;
                    double newY = oldPos.y() + 1;
                    qt.update(v, oldPos.x(), oldPos.y(), newX, newY);
                    hamt.put(v, new Position(newX, newY, 1000 + move));
                }
                qt.publish();
                hamt.publish();
            }

            var result = qt.rangeQuery(new BoundingBox(0, 0, 1000, 1000));
            assert result.size() == vehicleCount : "Expected " + vehicleCount + " points, got " + result.size();
            assert hamt.size() == vehicleCount : "Expected HAMT size " + vehicleCount + ", got " + hamt.size();

            System.out.println("PASS: multipleVehicleMovements");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: multipleVehicleMovements - " + t.getMessage());
            return false;
        }
    }

    private static boolean testVehicleCountStable() {
        try {
            CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 1000, 1000));
            HamtIndex hamt = new HamtIndex();

            int vehicleCount = 100;
            for (long v = 0; v < vehicleCount; v++) {
                qt.insert(v, v % 100, v / 100);
                hamt.put(v, new Position(v % 100, v / 100, 1000));
            }
            qt.publish();
            hamt.publish();

            // Simulate many updates
            for (int i = 0; i < 1000; i++) {
                long v = i % vehicleCount;
                Position oldPos = hamt.get(v);
                double newX = (oldPos.x() + 0.1) % 1000;
                double newY = (oldPos.y() + 0.1) % 1000;
                qt.update(v, oldPos.x(), oldPos.y(), newX, newY);
                hamt.put(v, new Position(newX, newY, 1000 + i));
            }
            qt.publish();
            hamt.publish();

            var result = qt.rangeQuery(new BoundingBox(0, 0, 1000, 1000));
            assert result.size() == vehicleCount : "Expected " + vehicleCount + " points, got " + result.size();
            assert hamt.size() == vehicleCount : "Expected HAMT size " + vehicleCount + ", got " + hamt.size();

            System.out.println("PASS: vehicleCountStable");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: vehicleCountStable - " + t.getMessage());
            return false;
        }
    }
}
