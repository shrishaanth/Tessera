package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NaiveIndexJUnitTest {

    @Test
    void insertAndRangeQuery() {
        NaiveIndex idx = new NaiveIndex();
        idx.insert(1, 10, 10);
        idx.insert(2, 20, 20);
        idx.insert(3, 15, 15);
        BoundingBox bbox = new BoundingBox(12, 12, 22, 22);
        var result = idx.rangeQuery(bbox);
        assertEquals(2, result.size());
        assertTrue(result.contains(2L));
        assertTrue(result.contains(3L));
    }

    @Test
    void removeRemovesPoint() {
        NaiveIndex idx = new NaiveIndex();
        idx.insert(1, 10, 10);
        idx.remove(1, 10, 10);
        assertTrue(idx.rangeQuery(new BoundingBox(0, 0, 100, 100)).isEmpty());
    }

    @Test
    void nearestReturnsClosest() {
        NaiveIndex idx = new NaiveIndex();
        idx.insert(1, 0, 0);
        idx.insert(2, 10, 0);
        NearestResult result = idx.nearest(0, 1);
        assertEquals(1, result.vehicleId());
        assertEquals(1.0, result.distance(), 0.001);
    }

    @Test
    void updateMovesPoint() {
        NaiveIndex idx = new NaiveIndex();
        idx.insert(1, 0, 0);
        idx.update(1, 0, 0, 100, 100);
        var result = idx.rangeQuery(new BoundingBox(0, 0, 50, 50));
        assertTrue(result.isEmpty());
        result = idx.rangeQuery(new BoundingBox(90, 90, 110, 110));
        assertEquals(1, result.size());
    }
}
