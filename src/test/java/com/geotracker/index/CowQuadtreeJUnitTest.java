package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CowQuadtreeJUnitTest {

    @Test
    void insertAndRangeQuery() {
        CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 100, 100));
        qt.insert(1, 10, 10);
        qt.insert(2, 20, 20);
        qt.insert(3, 15, 15);
        qt.publish();
        var result = qt.rangeQuery(new BoundingBox(12, 12, 22, 22));
        assertEquals(2, result.size());
        assertTrue(result.contains(2L));
        assertTrue(result.contains(3L));
    }

    @Test
    void removePoint() {
        CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 100, 100));
        qt.insert(1, 10, 10);
        qt.remove(1, 10, 10);
        qt.publish();
        assertTrue(qt.rangeQuery(new BoundingBox(0, 0, 100, 100)).isEmpty());
    }

    @Test
    void nearestReturnsClosest() {
        CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 100, 100));
        qt.insert(1, 0, 0);
        qt.insert(2, 10, 0);
        qt.publish();
        NearestResult result = qt.nearest(0, 1);
        assertEquals(1, result.vehicleId());
        assertEquals(1.0, result.distance(), 0.001);
    }

    @Test
    void updateMovesPoint() {
        CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 100, 100));
        qt.insert(1, 0, 0);
        qt.update(1, 0, 0, 100, 100);
        qt.publish();
        assertTrue(qt.rangeQuery(new BoundingBox(0, 0, 50, 50)).isEmpty());
        var result = qt.rangeQuery(new BoundingBox(90, 90, 110, 110));
        assertEquals(1, result.size());
    }

    @Test
    void publishIsolation() {
        CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 100, 100));
        qt.insert(1, 10, 10);
        qt.publish();
        qt.insert(2, 20, 20);
        var beforePublish = qt.rangeQuery(new BoundingBox(0, 0, 100, 100));
        assertEquals(1, beforePublish.size());
        qt.publish();
        var afterPublish = qt.rangeQuery(new BoundingBox(0, 0, 100, 100));
        assertEquals(2, afterPublish.size());
    }
}
