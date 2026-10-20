package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;
import com.geotracker.model.Position;
import com.geotracker.model.PositionUpdate;
import com.geotracker.util.RingBuffer;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for index defects found during the UI rebuild. */
class IndexCorrectnessRegressionTest {

    private static final BoundingBox WORLD = new BoundingBox(0, 0, 1000, 1000);

    @Test
    void gridRangeQueryReturnsOnlyPointsInsideTheBox() {
        GridIndex grid = new GridIndex(WORLD, 10, 10);
        grid.insert(1, 105, 105);  // inside the query
        grid.insert(2, 195, 195);  // same cell as the query's far corner, but outside the box
        List<Long> result = grid.rangeQuery(new BoundingBox(100, 100, 150, 150));
        assertEquals(List.of(1L), result);
    }

    @Test
    void gridNearestMatchesBruteForce() {
        // The old search stopped at the first non-empty ring, but a point in the next ring can be closer
        // than a point in the corner of the current one.
        Random rng = new Random(5);
        GridIndex grid = new GridIndex(WORLD, 20, 20);
        double[][] pts = new double[300][];
        for (int i = 0; i < pts.length; i++) {
            pts[i] = new double[]{rng.nextDouble() * 1000, rng.nextDouble() * 1000};
            grid.insert(i, pts[i][0], pts[i][1]);
        }
        for (int q = 0; q < 500; q++) {
            double x = rng.nextDouble() * 1000, y = rng.nextDouble() * 1000;
            double best = Double.MAX_VALUE;
            for (double[] p : pts) best = Math.min(best, Math.hypot(p[0] - x, p[1] - y));
            NearestResult r = grid.nearest(x, y);
            assertNotNull(r);
            assertEquals(best, r.distance(), 1e-9, "Wrong nearest neighbour for (" + x + "," + y + ")");
        }
    }

    @Test
    void gridNearestCornerCaseAcrossRings() {
        GridIndex grid = new GridIndex(new BoundingBox(0, 0, 100, 100), 10, 10);
        grid.insert(1, 19.9, 19.9);   // ring 1 (diagonal cell), ~14 away from the query
        grid.insert(2, 10.5, 1.0);    // ring 1 as well but closer in x...
        grid.insert(3, 0.5, 21.0);    // ring 2, distance ~12.0
        NearestResult r = grid.nearest(9.5, 9.5);
        double expected = Math.min(Math.hypot(19.9 - 9.5, 19.9 - 9.5),
                Math.min(Math.hypot(10.5 - 9.5, 1.0 - 9.5), Math.hypot(0.5 - 9.5, 21.0 - 9.5)));
        assertEquals(expected, r.distance(), 1e-9);
    }

    @Test
    void quadtreeLeafAcceptsManyVehiclesAtIdenticalCoordinates() {
        // Vehicles parked on the same road node share exact coordinates; a full leaf used to throw
        // "Leaf capacity exceeded", which killed the indexer thread.
        CowQuadtree qt = new CowQuadtree(WORLD);
        for (int i = 0; i < 100; i++) qt.insert(i, 500, 500);
        qt.insert(1000, 10, 10);
        qt.publish();
        assertEquals(100, qt.rangeQuery(new BoundingBox(499, 499, 501, 501)).size());
        assertEquals(101, qt.rangeQuery(WORLD).size());
    }

    @Test
    void publishedQuadtreeSnapshotIsUnaffectedByLaterWrites() {
        CowQuadtree qt = new CowQuadtree(WORLD);
        for (int i = 0; i < 200; i++) qt.insert(i, i * 4.0, i * 4.0);
        qt.publish();
        CowQuadtree snapshot = qt.snapshot();
        Set<Long> before = new HashSet<>(snapshot.rangeQuery(WORLD));

        for (int i = 0; i < 200; i++) qt.update(i, i * 4.0, i * 4.0, 999 - i, 1);
        for (int i = 200; i < 260; i++) qt.insert(i, 10, 990);
        qt.remove(5, 999 - 5, 1);

        assertEquals(before, new HashSet<>(snapshot.rangeQuery(WORLD)), "Snapshot changed after later writes");
        assertEquals(1, snapshot.rangeQuery(new BoundingBox(0, 0, 2, 2)).size());
        qt.publish();
        assertEquals(259, qt.rangeQuery(WORLD).size());
    }

    @Test
    void publishedHamtSnapshotIsUnaffectedByLaterWrites() {
        HamtIndex hamt = new HamtIndex();
        for (long i = 0; i < 500; i++) hamt.put(i, new Position(i, i, 1));
        hamt.publish();
        HamtIndex snapshot = hamt.snapshot();
        for (long i = 0; i < 500; i++) hamt.put(i, new Position(-1, -1, 2));
        for (long i = 500; i < 700; i++) hamt.put(i, new Position(0, 0, 3));
        for (long i = 0; i < 500; i++) {
            assertEquals(new Position(i, i, 1), snapshot.get(i));
        }
        assertNull(snapshot.get(600));
        assertEquals(500, snapshot.size());
        assertEquals(700, hamt.size());
    }

    @Test
    void clearingAnIndexerPublishesAnEmptySnapshot() throws InterruptedException {
        RingBuffer<PositionUpdate> buffer = new RingBuffer<>(1024);
        CowQuadtree qt = new CowQuadtree(WORLD);
        HamtIndex hamt = new HamtIndex();
        IndexerThread indexer = new IndexerThread(0, buffer, qt, hamt, 10, 5);
        indexer.setDaemon(true);
        indexer.start();
        try {
            for (int i = 0; i < 50; i++) buffer.offer(new PositionUpdate(i, i * 10, i * 10, System.currentTimeMillis()));
            long deadline = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < deadline
                    && (indexer.getPublishedSnapshot() == null || indexer.getPublishedSnapshot().hamt().size() < 50)) {
                Thread.sleep(5);
            }
            assertEquals(50, indexer.getPublishedSnapshot().hamt().size());

            assertTrue(indexer.clearAndWait(2_000));
            SpatialSnapshot snap = indexer.getPublishedSnapshot();
            assertEquals(0, snap.hamt().size());
            assertTrue(snap.quadtree().rangeQuery(WORLD).isEmpty());
        } finally {
            indexer.shutdown();
        }
    }

    @Test
    void indexerPublishesTrailingUpdatesWhenTheQueueGoesIdle() throws InterruptedException {
        RingBuffer<PositionUpdate> buffer = new RingBuffer<>(1024);
        IndexerThread indexer = new IndexerThread(0, buffer, new CowQuadtree(WORLD), new HamtIndex(), 1_000, 20);
        indexer.setDaemon(true);
        indexer.start();
        try {
            // Fewer updates than maxDirty: they must still become visible once the publish interval passes.
            for (int i = 0; i < 3; i++) buffer.offer(new PositionUpdate(i, 1, 1, System.currentTimeMillis()));
            Thread.sleep(300);
            assertNotNull(indexer.getPublishedSnapshot());
            assertEquals(3, indexer.getPublishedSnapshot().hamt().size());
        } finally {
            indexer.shutdown();
        }
    }
}
