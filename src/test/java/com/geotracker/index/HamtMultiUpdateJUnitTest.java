package com.geotracker.index;

import com.geotracker.model.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class HamtMultiUpdateJUnitTest {

    @Test
    void multipleUpdatesSameKey() {
        HamtIndex hamt = new HamtIndex();
        hamt.put(1, new Position(10, 20, 1000));
        hamt.put(1, new Position(30, 40, 2000));
        hamt.put(1, new Position(50, 60, 3000));
        hamt.put(1, new Position(70, 80, 4000));
        assertEquals(new Position(70, 80, 4000), hamt.get(1));
    }

    @Test
    void multipleUpdatesNoDuplicates() {
        HamtIndex hamt = new HamtIndex();
        for (int i = 0; i < 100; i++) {
            hamt.put(42, new Position(i, i, i));
        }
        assertEquals(new Position(99, 99, 99), hamt.get(42));
        assertEquals(1, hamt.size());
    }

    @Test
    void multipleDifferentKeys() {
        HamtIndex hamt = new HamtIndex();
        hamt.put(1, new Position(10, 20, 1000));
        hamt.put(2, new Position(30, 40, 2000));
        hamt.put(3, new Position(50, 60, 3000));
        assertEquals(new Position(10, 20, 1000), hamt.get(1));
        assertEquals(new Position(30, 40, 2000), hamt.get(2));
        assertEquals(new Position(50, 60, 3000), hamt.get(3));
        assertEquals(3, hamt.size());
    }

    @Test
    void sizeAfterUpdates() {
        HamtIndex hamt = new HamtIndex();
        hamt.put(1, new Position(10, 20, 1000));
        hamt.put(2, new Position(30, 40, 2000));
        assertEquals(2, hamt.size());
        hamt.put(1, new Position(11, 21, 1001));
        assertEquals(2, hamt.size());
        hamt.put(3, new Position(50, 60, 3000));
        assertEquals(3, hamt.size());
    }
}
