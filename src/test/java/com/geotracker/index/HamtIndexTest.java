package com.geotracker.index;

import com.geotracker.model.Position;

public class HamtIndexTest {
    public static void main(String[] args) {
        boolean allPassed = true;
        allPassed &= testPutAndGet();
        allPassed &= testGetMissingReturnsNull();
        allPassed &= testOverwriteUpdatesValue();
        allPassed &= testPersistenceOldRootUnchanged();
        allPassed &= testManyKeys();
        if (allPassed) {
            System.out.println("All HamtIndex tests passed");
        } else {
            System.out.println("Some HamtIndex tests FAILED");
            System.exit(1);
        }
    }

    private static boolean testPutAndGet() {
        try {
            HamtIndex hamt = new HamtIndex();
            Position pos = new Position(10, 20, 1000);
            hamt.put(42, pos);
            assert pos.equals(hamt.get(42)) : "Expected " + pos + " got " + hamt.get(42);
            System.out.println("PASS: putAndGet");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: putAndGet - " + t.getMessage());
            return false;
        }
    }

    private static boolean testGetMissingReturnsNull() {
        try {
            HamtIndex hamt = new HamtIndex();
            assert hamt.get(999) == null : "Expected null";
            System.out.println("PASS: getMissingReturnsNull");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: getMissingReturnsNull - " + t.getMessage());
            return false;
        }
    }

    private static boolean testOverwriteUpdatesValue() {
        try {
            HamtIndex hamt = new HamtIndex();
            hamt.put(42, new Position(10, 20, 1000));
            hamt.put(42, new Position(30, 40, 2000));
            assert new Position(30, 40, 2000).equals(hamt.get(42)) : "Expected updated value";
            System.out.println("PASS: overwriteUpdatesValue");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: overwriteUpdatesValue - " + t.getMessage());
            return false;
        }
    }

    private static boolean testPersistenceOldRootUnchanged() {
        try {
            HamtIndex hamt = new HamtIndex();
            hamt.put(42, new Position(10, 20, 1000));
            hamt.put(43, new Position(30, 40, 2000));
            assert hamt.get(43) == null : "Expected null before publish";
            hamt.publish();
            assert hamt.get(43) == null : "Expected null after publish";
            System.out.println("PASS: persistenceOldRootUnchanged");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: persistenceOldRootUnchanged - " + t.getMessage());
            return false;
        }
    }

    private static boolean testManyKeys() {
        try {
            HamtIndex hamt = new HamtIndex();
            for (int i = 0; i < 1000; i++) {
                hamt.put(i, new Position(i, i, i));
            }
            for (int i = 0; i < 1000; i++) {
                assert new Position(i, i, i).equals(hamt.get(i)) : "Mismatch at " + i;
            }
            System.out.println("PASS: manyKeys");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: manyKeys - " + t.getMessage());
            return false;
        }
    }
}
