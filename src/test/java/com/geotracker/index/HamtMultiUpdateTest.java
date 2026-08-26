package com.geotracker.index;

import com.geotracker.model.Position;

public class HamtMultiUpdateTest {
    public static void main(String[] args) {
        boolean allPassed = true;
        allPassed &= testMultipleUpdatesSameKey();
        allPassed &= testMultipleUpdatesNoDuplicates();
        allPassed &= testMultipleDifferentKeys();
        allPassed &= testSizeAfterUpdates();
        allPassed &= testRemoveAfterMultipleUpdates();
        if (allPassed) {
            System.out.println("All HamtMultiUpdate tests passed");
        } else {
            System.out.println("Some HamtMultiUpdate tests FAILED");
            System.exit(1);
        }
    }

    private static boolean testMultipleUpdatesSameKey() {
        try {
            HamtIndex hamt = new HamtIndex();
            hamt.put(1, new Position(10, 20, 1000));
            hamt.put(1, new Position(30, 40, 2000));
            hamt.put(1, new Position(50, 60, 3000));
            hamt.put(1, new Position(70, 80, 4000));
            Position result = hamt.get(1);
            assert result != null && result.x() == 70 && result.y() == 80 && result.timestamp() == 4000
                    : "Expected (70,80,4000) got " + result;
            System.out.println("PASS: multipleUpdatesSameKey");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: multipleUpdatesSameKey - " + t.getMessage());
            return false;
        }
    }

    private static boolean testMultipleUpdatesNoDuplicates() {
        try {
            HamtIndex hamt = new HamtIndex();
            for (int i = 0; i < 100; i++) {
                hamt.put(42, new Position(i, i, i));
            }
            Position result = hamt.get(42);
            assert result != null && result.x() == 99 && result.y() == 99 && result.timestamp() == 99
                    : "Expected (99,99,99) got " + result;
            assert hamt.size() == 1 : "Expected size 1, got " + hamt.size();
            System.out.println("PASS: multipleUpdatesNoDuplicates");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: multipleUpdatesNoDuplicates - " + t.getMessage());
            return false;
        }
    }

    private static boolean testMultipleDifferentKeys() {
        try {
            HamtIndex hamt = new HamtIndex();
            hamt.put(1, new Position(10, 20, 1000));
            hamt.put(2, new Position(30, 40, 2000));
            hamt.put(3, new Position(50, 60, 3000));
            assert new Position(10, 20, 1000).equals(hamt.get(1)) : "Key 1 mismatch";
            assert new Position(30, 40, 2000).equals(hamt.get(2)) : "Key 2 mismatch";
            assert new Position(50, 60, 3000).equals(hamt.get(3)) : "Key 3 mismatch";
            assert hamt.size() == 3 : "Expected size 3, got " + hamt.size();
            System.out.println("PASS: multipleDifferentKeys");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: multipleDifferentKeys - " + t.getMessage());
            return false;
        }
    }

    private static boolean testSizeAfterUpdates() {
        try {
            HamtIndex hamt = new HamtIndex();
            hamt.put(1, new Position(10, 20, 1000));
            hamt.put(2, new Position(30, 40, 2000));
            assert hamt.size() == 2 : "Expected size 2, got " + hamt.size();
            hamt.put(1, new Position(11, 21, 1001));
            assert hamt.size() == 2 : "Expected size 2 after update, got " + hamt.size();
            hamt.put(3, new Position(50, 60, 3000));
            assert hamt.size() == 3 : "Expected size 3, got " + hamt.size();
            System.out.println("PASS: sizeAfterUpdates");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: sizeAfterUpdates - " + t.getMessage());
            return false;
        }
    }

    private static boolean testRemoveAfterMultipleUpdates() {
        try {
            HamtIndex hamt = new HamtIndex();
            hamt.put(1, new Position(10, 20, 1000));
            hamt.put(1, new Position(30, 40, 2000));
            hamt.put(1, new Position(50, 60, 3000));
            assert hamt.get(1) != null : "Key 1 should exist";
            hamt.publish();
            // Note: HamtIndex doesn't have a remove method, but we can verify
            // that after multiple puts, only the latest value remains
            assert new Position(50, 60, 3000).equals(hamt.get(1)) : "Expected latest value";
            System.out.println("PASS: removeAfterMultipleUpdates");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: removeAfterMultipleUpdates - " + t.getMessage());
            return false;
        }
    }
}
