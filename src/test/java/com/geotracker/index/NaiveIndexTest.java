package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;

public class NaiveIndexTest {
    public static void main(String[] args) {
        boolean allPassed = true;
        allPassed &= testInsertAndRangeQuery();
        allPassed &= testRemoveRemovesPoint();
        allPassed &= testNearestReturnsClosest();
        allPassed &= testUpdateMovesPoint();
        if (allPassed) {
            System.out.println("All NaiveIndex tests passed");
        } else {
            System.out.println("Some NaiveIndex tests FAILED");
            System.exit(1);
        }
    }

    private static boolean testInsertAndRangeQuery() {
        try {
            NaiveIndex idx = new NaiveIndex();
            idx.insert(1, 10, 10);
            idx.insert(2, 20, 20);
            idx.insert(3, 15, 15);
            BoundingBox bbox = new BoundingBox(12, 12, 22, 22);
            var result = idx.rangeQuery(bbox);
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

    private static boolean testRemoveRemovesPoint() {
        try {
            NaiveIndex idx = new NaiveIndex();
            idx.insert(1, 10, 10);
            idx.remove(1, 10, 10);
            assert idx.rangeQuery(new BoundingBox(0, 0, 100, 100)).isEmpty() : "Expected empty";
            System.out.println("PASS: removeRemovesPoint");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: removeRemovesPoint - " + t.getMessage());
            return false;
        }
    }

    private static boolean testNearestReturnsClosest() {
        try {
            NaiveIndex idx = new NaiveIndex();
            idx.insert(1, 0, 0);
            idx.insert(2, 10, 0);
            NearestResult result = idx.nearest(0, 1);
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
            NaiveIndex idx = new NaiveIndex();
            idx.insert(1, 0, 0);
            idx.update(1, 0, 0, 100, 100);
            var result = idx.rangeQuery(new BoundingBox(0, 0, 50, 50));
            assert result.isEmpty() : "Expected empty";
            result = idx.rangeQuery(new BoundingBox(90, 90, 110, 110));
            assert result.size() == 1 : "Expected 1, got " + result.size();
            System.out.println("PASS: updateMovesPoint");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: updateMovesPoint - " + t.getMessage());
            return false;
        }
    }
}
