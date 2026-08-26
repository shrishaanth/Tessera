package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SnapshotConsistencyJUnitTest {

    @Test
    void snapshotContainsCurrentState() {
        CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 1000, 1000));
        HamtIndex hamt = new HamtIndex();

        qt.insert(1, 100, 100);
        hamt.put(1, new Position(100, 100, 1000));
        qt.publish();
        hamt.publish();

        CowQuadtree qtSnapshot = qt.snapshot();
        HamtIndex hamtSnapshot = hamt.snapshot();

        assertEquals(1, qtSnapshot.rangeQuery(new BoundingBox(0, 0, 1000, 1000)).size());
        assertEquals(new Position(100, 100, 1000), hamtSnapshot.get(1));
    }

    @Test
    void snapshotIsIsolatedFromFutureUpdates() {
        CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 1000, 1000));
        HamtIndex hamt = new HamtIndex();

        qt.insert(1, 100, 100);
        hamt.put(1, new Position(100, 100, 1000));
        qt.publish();
        hamt.publish();

        CowQuadtree qtSnapshot = qt.snapshot();
        HamtIndex hamtSnapshot = hamt.snapshot();

        qt.insert(2, 200, 200);
        hamt.put(2, new Position(200, 200, 2000));
        qt.publish();
        hamt.publish();

        assertEquals(1, qtSnapshot.rangeQuery(new BoundingBox(0, 0, 1000, 1000)).size());
        assertNull(hamtSnapshot.get(2));
    }
}
