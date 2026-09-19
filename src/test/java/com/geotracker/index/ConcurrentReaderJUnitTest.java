package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentReaderJUnitTest {

    @Test
    void concurrentReadersDoNotCorrupt() throws Exception {
        int readerThreads = 16;
        int iterations = 1000;
        BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
        CowQuadtree quadtree = new CowQuadtree(bounds);

        for (int i = 0; i < 1000; i++) {
            quadtree.insert(i, i % 1000, i / 1000);
        }
        quadtree.publish();

        ExecutorService executor = Executors.newFixedThreadPool(readerThreads);
        CountDownLatch latch = new CountDownLatch(readerThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < readerThreads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        double cx = Math.random() * 900;
                        double cy = Math.random() * 900;
                        BoundingBox bbox = new BoundingBox(cx, cy, cx + 100, cy + 100);
                        List<Long> result = quadtree.rangeQuery(bbox);
                        if (result == null) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertEquals(0, errors.get(), "Concurrent readers encountered errors");
    }
}
