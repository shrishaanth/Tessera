package com.geotracker.dashboard;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MultiShardDashboardJUnitTest {

    @Test
    void multiShardVehicleAggregation() {
        BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
        CowQuadtree[] quadtrees = new CowQuadtree[2];
        HamtIndex[] hamts = new HamtIndex[2];

        for (int i = 0; i < 2; i++) {
            quadtrees[i] = new CowQuadtree(bounds);
            hamts[i] = new HamtIndex();
        }

        quadtrees[0].insert(0, 100, 100);
        quadtrees[0].insert(2, 200, 200);
        quadtrees[0].insert(4, 300, 300);
        hamts[0].put(0, new Position(100, 100, 1000));
        hamts[0].put(2, new Position(200, 200, 2000));
        hamts[0].put(4, new Position(300, 300, 3000));
        quadtrees[0].publish();
        hamts[0].publish();

        quadtrees[1].insert(1, 400, 400);
        quadtrees[1].insert(3, 500, 500);
        quadtrees[1].insert(5, 600, 600);
        hamts[1].put(1, new Position(400, 400, 1000));
        hamts[1].put(3, new Position(500, 500, 2000));
        hamts[1].put(5, new Position(600, 600, 3000));
        quadtrees[1].publish();
        hamts[1].publish();

        int totalVehicles = 0;
        for (HamtIndex h : hamts) {
            totalVehicles += h.size();
        }
        assertEquals(6, totalVehicles);

        var allVehicles = new java.util.ArrayList<Long>();
        for (CowQuadtree qt : quadtrees) {
            allVehicles.addAll(qt.rangeQuery(bounds));
        }
        assertEquals(6, allVehicles.size());
    }

    @Test
    void multiShardVehicleCount() {
        BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
        CowQuadtree[] quadtrees = new CowQuadtree[2];
        HamtIndex[] hamts = new HamtIndex[2];

        for (int i = 0; i < 2; i++) {
            quadtrees[i] = new CowQuadtree(bounds);
            hamts[i] = new HamtIndex();
        }

        for (long v = 0; v < 100; v++) {
            quadtrees[0].insert(v, v % 100, v / 100);
            hamts[0].put(v, new Position(v % 100, v / 100, 1000));
        }
        quadtrees[0].publish();
        hamts[0].publish();

        for (long v = 100; v < 200; v++) {
            quadtrees[1].insert(v, v % 100, v / 100);
            hamts[1].put(v, new Position(v % 100, v / 100, 1000));
        }
        quadtrees[1].publish();
        hamts[1].publish();

        int totalVehicles = 0;
        for (HamtIndex h : hamts) {
            totalVehicles += h.size();
        }
        assertEquals(200, totalVehicles);
    }

    @Test
    void multiShardCurrentPositions() {
        BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
        CowQuadtree[] quadtrees = new CowQuadtree[2];
        HamtIndex[] hamts = new HamtIndex[2];

        for (int i = 0; i < 2; i++) {
            quadtrees[i] = new CowQuadtree(bounds);
            hamts[i] = new HamtIndex();
        }

        quadtrees[0].insert(0, 100, 100);
        hamts[0].put(0, new Position(100, 100, 1000));
        quadtrees[0].publish();
        hamts[0].publish();

        Position oldPos = hamts[0].get(0);
        quadtrees[0].update(0, oldPos.x(), oldPos.y(), 200, 200);
        hamts[0].put(0, new Position(200, 200, 2000));
        quadtrees[0].publish();
        hamts[0].publish();

        oldPos = hamts[0].get(0);
        quadtrees[0].update(0, oldPos.x(), oldPos.y(), 300, 300);
        hamts[0].put(0, new Position(300, 300, 3000));
        quadtrees[0].publish();
        hamts[0].publish();

        quadtrees[1].insert(1, 400, 400);
        hamts[1].put(1, new Position(400, 400, 1000));
        quadtrees[1].publish();
        hamts[1].publish();

        oldPos = hamts[1].get(1);
        quadtrees[1].update(1, oldPos.x(), oldPos.y(), 500, 500);
        hamts[1].put(1, new Position(500, 500, 2000));
        quadtrees[1].publish();
        hamts[1].publish();

        oldPos = hamts[1].get(1);
        quadtrees[1].update(1, oldPos.x(), oldPos.y(), 600, 600);
        hamts[1].put(1, new Position(600, 600, 3000));
        quadtrees[1].publish();
        hamts[1].publish();

        var allVehicles = new java.util.ArrayList<Long>();
        for (CowQuadtree qt : quadtrees) {
            allVehicles.addAll(qt.rangeQuery(bounds));
        }
        assertEquals(2, allVehicles.size());

        int totalVehicles = 0;
        for (HamtIndex h : hamts) {
            totalVehicles += h.size();
        }
        assertEquals(2, totalVehicles);
    }
}
