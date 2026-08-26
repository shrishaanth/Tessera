package com.geotracker.index;

import com.geotracker.model.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentReaderTest {
    public static void main(String[] args) throws Exception {
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
        List<Thread> threads = new ArrayList<>();

        for (int t = 0; t < readerThreads; t++) {
            Thread thread = new Thread(() -> {
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
            threads.add(thread);
            thread.start();
        }

        latch.await();
        executor.shutdown();

        if (errors.get() == 0) {
            System.out.println("PASS: ConcurrentReaderTest - " + readerThreads + " readers completed without errors");
        } else {
            System.out.println("FAIL: ConcurrentReaderTest - " + errors.get() + " errors encountered");
            System.exit(1);
        }
    }
}
