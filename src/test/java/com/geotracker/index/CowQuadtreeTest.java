package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;
import com.geotracker.model.Position;

public class CowQuadtreeTest {
    public static void main(String[] args) {
        boolean allPassed = true;
        allPassed &= testInsertAndRangeQuery();
        allPassed &= testRemovePoint();
        allPassed &= testNearestReturnsClosest();
        allPassed &= testUpdateMovesPoint();
        allPassed &= testPublishIsolation();
        allPassed &= testManyPoints();
        if (allPassed) {
            System.out.println("All CowQuadtree tests passed");
        } else {
            System.out.println("Some CowQuadtree tests FAILED");
            System.exit(1);
        }
    }

    private static boolean testInsertAndRangeQuery() {
        try {
            CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 100, 100));
            qt.insert(1, 10, 10);
            qt.insert(2, 20, 20);
            qt.insert(3, 15, 15);
            qt.publish();
            var result = qt.rangeQuery(new BoundingBox(12, 12, 22, 22));
            assert result.size() == 2 : "Expected 2, got " + result.size();
            assert result.contains(2L) : "Expected 2";
            assert result.contains(3L) : "Expected 3";
            System.out.println("PASS: insertAndRangeQuery");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: insertAndRangeQuery - " + t.getMessage());
            return false;
        }
    }

    private static boolean testRemovePoint() {
        try {
            CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 100, 100));
            qt.insert(1, 10, 10);
            qt.remove(1, 10, 10);
            qt.publish();
            assert qt.rangeQuery(new BoundingBox(0, 0, 100, 100)).isEmpty() : "Expected empty";
            System.out.println("PASS: removePoint");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: removePoint - " + t.getMessage());
            return false;
        }
    }

    private static boolean testNearestReturnsClosest() {
        try {
            CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 100, 100));
            qt.insert(1, 0, 0);
            qt.insert(2, 10, 0);
            qt.publish();
            NearestResult result = qt.nearest(0, 1);
            assert result.vehicleId() == 1 : "Expected 1, got " + result.vehicleId();
            assert Math.abs(result.distance() - 1.0) < 0.001 : "Expected 1.0, got " + result.distance();
            System.out.println("PASS: nearestReturnsClosest");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: nearestReturnsClosest - " + t.getMessage());
            return false;
        }
    }

    private static boolean testUpdateMovesPoint() {
        try {
            CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 100, 100));
            qt.insert(1, 0, 0);
            qt.update(1, 0, 0, 100, 100);
            qt.publish();
            assert qt.rangeQuery(new BoundingBox(0, 0, 50, 50)).isEmpty() : "Expected empty";
            var result = qt.rangeQuery(new BoundingBox(90, 90, 110, 110));
            assert result.size() == 1 : "Expected 1, got " + result.size();
            System.out.println("PASS: updateMovesPoint");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: updateMovesPoint - " + t.getMessage());
            return false;
        }
    }

    private static boolean testPublishIsolation() {
        try {
            CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 100, 100));
            qt.insert(1, 10, 10);
            qt.publish();
            qt.insert(2, 20, 20);
            var beforePublish = qt.rangeQuery(new BoundingBox(0, 0, 100, 100));
            assert beforePublish.size() == 1 : "Expected 1, got " + beforePublish.size();
            qt.publish();
            var afterPublish = qt.rangeQuery(new BoundingBox(0, 0, 100, 100));
            assert afterPublish.size() == 2 : "Expected 2, got " + afterPublish.size();
            System.out.println("PASS: publishIsolation");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: publishIsolation - " + t.getMessage());
            return false;
        }
    }

    private static boolean testManyPoints() {
        try {
            CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 1000, 1000));
            for (int i = 0; i < 100; i++) {
                qt.insert(i, i % 100, i / 100);
            }
            qt.publish();
            var result = qt.rangeQuery(new BoundingBox(0, 0, 1000, 1000));
            assert result.size() == 100 : "Expected 100, got " + result.size();
            System.out.println("PASS: manyPoints");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: manyPoints - " + t.getMessage());
            return false;
        }
    }
}
