package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyStressTest {

    @Test
    @Timeout(10)
    void concurrentInsertAndQuery() throws Exception {
        BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
        CowQuadtree qt = new CowQuadtree(bounds);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger insertCount = new AtomicInteger(0);
        AtomicInteger queryCount = new AtomicInteger(0);

        // Writer thread
        Thread writer = new Thread(() -> {
            Random rng = new Random(42);
            while (running.get()) {
                int vid = insertCount.getAndIncrement();
                qt.insert(vid, rng.nextDouble() * 1000, rng.nextDouble() * 1000);
                if (vid % 50 == 0) qt.publish();
            }
        });

        // Reader threads
        Thread[] readers = new Thread[4];
        for (int r = 0; r < readers.length; r++) {
            final int readerId = r;
            readers[r] = new Thread(() -> {
                Random rng = new Random(readerId);
                while (running.get()) {
                    double cx = rng.nextDouble() * 1000;
                    double cy = rng.nextDouble() * 1000;
                    List<Long> result = qt.rangeQuery(new BoundingBox(cx - 100, cy - 100, cx + 100, cy + 100));
                    assertNotNull(result);
                    queryCount.incrementAndGet();
                }
            });
        }

        writer.start();
        for (Thread r : readers) r.start();

        Thread.sleep(2000);
        running.set(false);

        writer.join(2000);
        for (Thread r : readers) r.join(2000);

        assertTrue(insertCount.get() > 100, "Expected >100 inserts, got " + insertCount.get());
        assertTrue(queryCount.get() > 100, "Expected >100 queries, got " + queryCount.get());
    }

    @Test
    @Timeout(10)
    void concurrentUpdateAndQuery() throws Exception {
        BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
        CowQuadtree qt = new CowQuadtree(bounds);
        int vehicleCount = 100;
        Random rng = new Random(42);

        // Pre-populate
        double[] xs = new double[vehicleCount];
        double[] ys = new double[vehicleCount];
        for (int i = 0; i < vehicleCount; i++) {
            xs[i] = rng.nextDouble() * 1000;
            ys[i] = rng.nextDouble() * 1000;
            qt.insert(i, xs[i], ys[i]);
        }
        qt.publish();

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger errors = new AtomicInteger(0);

        // Updater thread
        Thread updater = new Thread(() -> {
            Random updRng = new Random(123);
            while (running.get()) {
                int vid = updRng.nextInt(vehicleCount);
                double newX = updRng.nextDouble() * 1000;
                double newY = updRng.nextDouble() * 1000;
                synchronized (qt) {
                    qt.update(vid, xs[vid], ys[vid], newX, newY);
                    xs[vid] = newX;
                    ys[vid] = newY;
                }
                qt.publish();
            }
        });

        // Query thread
        Thread querier = new Thread(() -> {
            Random qRng = new Random(456);
            while (running.get()) {
                try {
                    List<Long> result = qt.rangeQuery(bounds);
                    assertNotNull(result);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
        });

        updater.start();
        querier.start();

        Thread.sleep(2000);
        running.set(false);

        updater.join(2000);
        querier.join(2000);

        assertEquals(0, errors.get(), "No errors expected during concurrent access");
    }

    @Test
    @Timeout(10)
    void hamtConcurrentPutAndGet() throws Exception {
        HamtIndex hamt = new HamtIndex();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger errors = new AtomicInteger(0);
        int vehicleCount = 100;

        // Pre-populate
        for (int i = 0; i < vehicleCount; i++) {
            hamt.put(i, new com.geotracker.model.Position(i, i, 0));
        }
        hamt.publish();

        Thread writer = new Thread(() -> {
            Random rng = new Random(42);
            while (running.get()) {
                int vid = rng.nextInt(vehicleCount);
                hamt.put(vid, new com.geotracker.model.Position(rng.nextDouble(), rng.nextDouble(), System.currentTimeMillis()));
                if (rng.nextInt(10) == 0) hamt.publish();
            }
        });

        Thread[] readers = new Thread[3];
        for (int r = 0; r < readers.length; r++) {
            final int readerId = r;
            readers[r] = new Thread(() -> {
                Random rng = new Random(readerId);
                while (running.get()) {
                    try {
                        int vid = rng.nextInt(vehicleCount);
                        hamt.get(vid);  // should not throw
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            });
        }

        writer.start();
        for (Thread r : readers) r.start();

        Thread.sleep(2000);
        running.set(false);

        writer.join(2000);
        for (Thread r : readers) r.join(2000);

        assertEquals(0, errors.get());
    }
}
