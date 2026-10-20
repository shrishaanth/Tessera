package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential testing: compare CowQuadtree, GridIndex, and NaiveIndex
 * against a brute-force reference to verify correctness.
 */
class SpatialDifferentialTest {

    private static final int VEHICLE_COUNT = 200;
    private static final int QUERY_COUNT = 100;
    private static final long SEED = 42;

    @Test
    void allIndexesAgreeOnRangeQueries() {
        BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
        Random rng = new Random(SEED);

        CowQuadtree qt = new CowQuadtree(bounds);
        GridIndex grid = new GridIndex(bounds, 20, 20);
        NaiveIndex naive = new NaiveIndex();

        // Reference: brute-force list
        double[] xs = new double[VEHICLE_COUNT];
        double[] ys = new double[VEHICLE_COUNT];

        for (int i = 0; i < VEHICLE_COUNT; i++) {
            double x = rng.nextDouble() * 1000;
            double y = rng.nextDouble() * 1000;
            xs[i] = x;
            ys[i] = y;
            qt.insert(i, x, y);
            grid.insert(i, x, y);
            naive.insert(i, x, y);
        }
        qt.publish();

        for (int q = 0; q < QUERY_COUNT; q++) {
            double cx = rng.nextDouble() * 1000;
            double cy = rng.nextDouble() * 1000;
            double w = rng.nextDouble() * 200;
            double h = rng.nextDouble() * 200;
            BoundingBox query = new BoundingBox(
                    Math.max(0, cx - w / 2), Math.max(0, cy - h / 2),
                    Math.min(1000, cx + w / 2), Math.min(1000, cy + h / 2));

            // Brute force
            Set<Long> expected = new TreeSet<>();
            for (int i = 0; i < VEHICLE_COUNT; i++) {
                if (query.contains(xs[i], ys[i])) expected.add((long) i);
            }

            Set<Long> qtResult = new TreeSet<>(qt.rangeQuery(query));
            Set<Long> gridResult = new TreeSet<>(grid.rangeQuery(query));
            Set<Long> naiveResult = new TreeSet<>(naive.rangeQuery(query));

            assertEquals(expected, qtResult,
                    "CowQuadtree disagrees with brute force on query " + q + ": " + query);
            assertEquals(expected, naiveResult,
                    "NaiveIndex disagrees with brute force on query " + q + ": " + query);
            // GridIndex used to return every vehicle in the boundary cells (a superset); it now filters exactly.
            assertEquals(expected, gridResult,
                    "GridIndex disagrees with brute force on query " + q + ": " + query);
        }
    }

    @Test
    void indexesHandleUpdatesCorrectly() {
        BoundingBox bounds = new BoundingBox(0, 0, 100, 100);
        CowQuadtree qt = new CowQuadtree(bounds);
        NaiveIndex naive = new NaiveIndex();

        // Insert
        qt.insert(1, 50, 50);
        naive.insert(1, 50, 50);

        // Update to new position
        qt.update(1, 50, 50, 75, 75);
        naive.update(1, 50, 50, 75, 75);
        qt.publish();

        BoundingBox oldArea = new BoundingBox(40, 40, 60, 60);
        BoundingBox newArea = new BoundingBox(70, 70, 80, 80);

        assertTrue(qt.rangeQuery(oldArea).isEmpty(), "Old position should be empty");
        assertTrue(naive.rangeQuery(oldArea).isEmpty(), "Naive: old position should be empty");
        assertEquals(1, qt.rangeQuery(newArea).size(), "New position should have vehicle");
        assertEquals(1, naive.rangeQuery(newArea).size(), "Naive: new position should have vehicle");
    }

    @Test
    void indexesHandleRemoveCorrectly() {
        BoundingBox bounds = new BoundingBox(0, 0, 100, 100);
        CowQuadtree qt = new CowQuadtree(bounds);
        NaiveIndex naive = new NaiveIndex();

        qt.insert(1, 50, 50);
        naive.insert(1, 50, 50);

        qt.remove(1, 50, 50);
        naive.remove(1, 50, 50);
        qt.publish();

        BoundingBox area = new BoundingBox(0, 0, 100, 100);
        assertTrue(qt.rangeQuery(area).isEmpty());
        assertTrue(naive.rangeQuery(area).isEmpty());
    }

    @Test
    void emptyQueryReturnsEmpty() {
        BoundingBox bounds = new BoundingBox(0, 0, 100, 100);
        CowQuadtree qt = new CowQuadtree(bounds);
        qt.publish();

        assertTrue(qt.rangeQuery(new BoundingBox(10, 10, 20, 20)).isEmpty());
    }

    @Test
    void outOfBoundsInsertIgnored() {
        BoundingBox bounds = new BoundingBox(0, 0, 100, 100);
        CowQuadtree qt = new CowQuadtree(bounds);
        qt.insert(1, -10, -10);  // out of bounds
        qt.publish();
        assertTrue(qt.rangeQuery(bounds).isEmpty());
    }

    @Test
    void boundaryPointsIncluded() {
        BoundingBox bounds = new BoundingBox(0, 0, 100, 100);
        CowQuadtree qt = new CowQuadtree(bounds);
        qt.insert(1, 0, 0);      // corner
        qt.insert(2, 100, 100);  // opposite corner
        qt.insert(3, 50, 0);     // edge
        qt.publish();

        List<Long> result = qt.rangeQuery(bounds);
        assertEquals(3, result.size());
    }

    @Test
    void duplicateIdHandling() {
        BoundingBox bounds = new BoundingBox(0, 0, 100, 100);
        CowQuadtree qt = new CowQuadtree(bounds);
        qt.insert(1, 50, 50);
        qt.insert(1, 50, 50);  // duplicate
        qt.publish();

        List<Long> result = qt.rangeQuery(bounds);
        // Both insertions are stored (quadtree doesn't deduplicate by ID)
        assertEquals(2, result.size());
    }
}
