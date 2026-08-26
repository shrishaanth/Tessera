package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class QuadtreeStalePositionJUnitTest {

    @Test
    void noStalePositionsAfterUpdates() {
        CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 1000, 1000));
        HamtIndex hamt = new HamtIndex();
        long vehicleId = 1;

        qt.insert(vehicleId, 100, 100);
        hamt.put(vehicleId, new Position(100, 100, 1000));
        qt.publish();
        hamt.publish();

        Position oldPos = hamt.get(vehicleId);
        qt.update(vehicleId, oldPos.x(), oldPos.y(), 200, 200);
        hamt.put(vehicleId, new Position(200, 200, 2000));
        qt.publish();
        hamt.publish();

        oldPos = hamt.get(vehicleId);
        qt.update(vehicleId, oldPos.x(), oldPos.y(), 300, 300);
        hamt.put(vehicleId, new Position(300, 300, 3000));
        qt.publish();
        hamt.publish();

        var result = qt.rangeQuery(new BoundingBox(0, 0, 1000, 1000));
        assertEquals(1, result.size());
        assertTrue(result.contains(vehicleId));
        assertEquals(new Position(300, 300, 3000), hamt.get(vehicleId));

        var oldPositions = qt.rangeQuery(new BoundingBox(0, 0, 150, 150));
        assertTrue(oldPositions.isEmpty());
    }

    @Test
    void multipleVehicleMovements() {
        CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 1000, 1000));
        HamtIndex hamt = new HamtIndex();
        int vehicleCount = 50;

        for (long v = 0; v < vehicleCount; v++) {
            qt.insert(v, v * 10, v * 10);
            hamt.put(v, new Position(v * 10, v * 10, 1000));
        }
        qt.publish();
        hamt.publish();

        for (int move = 0; move < 10; move++) {
            for (long v = 0; v < vehicleCount; v++) {
                Position oldPos = hamt.get(v);
                double newX = oldPos.x() + 1;
                double newY = oldPos.y() + 1;
                qt.update(v, oldPos.x(), oldPos.y(), newX, newY);
                hamt.put(v, new Position(newX, newY, 1000 + move));
            }
            qt.publish();
            hamt.publish();
        }

        var result = qt.rangeQuery(new BoundingBox(0, 0, 1000, 1000));
        assertEquals(vehicleCount, result.size());
        assertEquals(vehicleCount, hamt.size());
    }

    @Test
    void vehicleCountStable() {
        CowQuadtree qt = new CowQuadtree(new BoundingBox(0, 0, 1000, 1000));
        HamtIndex hamt = new HamtIndex();
        int vehicleCount = 100;

        for (long v = 0; v < vehicleCount; v++) {
            qt.insert(v, v % 100, v / 100);
            hamt.put(v, new Position(v % 100, v / 100, 1000));
        }
        qt.publish();
        hamt.publish();

        for (int i = 0; i < 1000; i++) {
            long v = i % vehicleCount;
            Position oldPos = hamt.get(v);
            double newX = (oldPos.x() + 0.1) % 1000;
            double newY = (oldPos.y() + 0.1) % 1000;
            qt.update(v, oldPos.x(), oldPos.y(), newX, newY);
            hamt.put(v, new Position(newX, newY, 1000 + i));
        }
        qt.publish();
        hamt.publish();

        var result = qt.rangeQuery(new BoundingBox(0, 0, 1000, 1000));
        assertEquals(vehicleCount, result.size());
        assertEquals(vehicleCount, hamt.size());
    }
}
