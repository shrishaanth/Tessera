package com.geotracker.index;

import com.geotracker.model.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class HamtIndexJUnitTest {

    @Test
    void putAndGet() {
        HamtIndex hamt = new HamtIndex();
        Position pos = new Position(10, 20, 1000);
        hamt.put(42, pos);
        assertEquals(pos, hamt.get(42));
    }

    @Test
    void getMissingReturnsNull() {
        HamtIndex hamt = new HamtIndex();
        assertNull(hamt.get(999));
    }

    @Test
    void overwriteUpdatesValue() {
        HamtIndex hamt = new HamtIndex();
        hamt.put(42, new Position(10, 20, 1000));
        hamt.put(42, new Position(30, 40, 2000));
        assertEquals(new Position(30, 40, 2000), hamt.get(42));
    }

    @Test
    void manyKeys() {
        HamtIndex hamt = new HamtIndex();
        for (int i = 0; i < 1000; i++) {
            hamt.put(i, new Position(i, i, i));
        }
        for (int i = 0; i < 1000; i++) {
            assertEquals(new Position(i, i, i), hamt.get(i));
        }
    }

    @Test
    void multipleUpdatesSameKey() {
        HamtIndex hamt = new HamtIndex();
        hamt.put(1, new Position(10, 20, 1000));
        hamt.put(1, new Position(30, 40, 2000));
        hamt.put(1, new Position(50, 60, 3000));
        hamt.put(1, new Position(70, 80, 4000));
        assertEquals(new Position(70, 80, 4000), hamt.get(1));
        assertEquals(1, hamt.size());
    }

    @Test
    void collisionMaxDepthUpdate() {
        HamtIndex hamt = new HamtIndex();
        for (int i = 0; i < 100; i++) {
            hamt.put(i, new Position(i, i, i));
        }
        for (int i = 0; i < 100; i++) {
            hamt.put(i, new Position(i + 1, i + 1, i + 1));
        }
        for (int i = 0; i < 100; i++) {
            assertEquals(new Position(i + 1, i + 1, i + 1), hamt.get(i));
        }
        assertEquals(100, hamt.size());
    }

    @Test
    void putInLeafOverwriteDoesNotDuplicate() {
        HamtIndex hamt = new HamtIndex();
        long key = 42;
        hamt.put(key, new Position(1, 1, 1));
        hamt.put(key, new Position(2, 2, 2));
        hamt.put(key, new Position(3, 3, 3));
        assertEquals(new Position(3, 3, 3), hamt.get(key));
        assertEquals(1, hamt.size());
    }

    @Test
    void manyCollidingKeysTriggerSplitting() {
        HamtIndex hamt = new HamtIndex();
        int count = 20;
        for (int i = 0; i < count; i++) {
            long key = (long) i << 5;
            hamt.put(key, new Position(i, i, i));
        }
        for (int i = 0; i < count; i++) {
            long key = (long) i << 5;
            assertEquals(new Position(i, i, i), hamt.get(key));
        }
        assertEquals(count, hamt.size());
    }
}
