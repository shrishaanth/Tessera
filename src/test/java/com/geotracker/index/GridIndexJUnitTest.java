package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class GridIndexJUnitTest {

    @Test
    void nearestReturnsClosest() {
        GridIndex idx = new GridIndex(new BoundingBox(0, 0, 100, 100), 10, 10);
        idx.insert(1, 0, 0);
        idx.insert(2, 10, 0);
        idx.insert(3, 5, 5);
        NearestResult result = idx.nearest(0, 1);
        assertNotNull(result);
        assertEquals(1, result.vehicleId());
        assertEquals(1.0, result.distance(), 0.001);
    }

    @Test
    void nearestEmptyGridReturnsNull() {
        GridIndex idx = new GridIndex(new BoundingBox(0, 0, 100, 100), 10, 10);
        assertNull(idx.nearest(50, 50));
    }

    @Test
    void nearestWithUpdates() {
        GridIndex idx = new GridIndex(new BoundingBox(0, 0, 100, 100), 10, 10);
        idx.insert(1, 0, 0);
        idx.insert(2, 100, 100);
        assertEquals(0, idx.nearest(0, 0).distance(), 0.001);
        idx.remove(1, 0, 0);
        idx.insert(1, 99, 99);
        assertEquals(2, idx.nearest(100, 100).vehicleId());
    }
}
