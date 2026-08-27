package com.geotracker.util;

import com.geotracker.model.PositionUpdate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RingBufferJUnitTest {

    @Test
    void offerPollFifo() {
        RingBuffer rb = new RingBuffer(4);
        PositionUpdate u1 = new PositionUpdate(1, 0, 0, 0);
        PositionUpdate u2 = new PositionUpdate(2, 1, 1, 1);
        assertTrue(rb.offer(u1));
        assertTrue(rb.offer(u2));
        assertEquals(u1, rb.poll());
        assertEquals(u2, rb.poll());
        assertNull(rb.poll());
    }

    @Test
    void offerWrapOverwritesOldest() {
        RingBuffer rb = new RingBuffer(2);
        PositionUpdate u1 = new PositionUpdate(1, 0, 0, 0);
        PositionUpdate u2 = new PositionUpdate(2, 1, 1, 1);
        PositionUpdate u3 = new PositionUpdate(3, 2, 2, 2);
        assertTrue(rb.offer(u1));
        assertTrue(rb.offer(u2));
        assertTrue(rb.offer(u3));
        assertEquals(u2, rb.poll());
        assertEquals(u3, rb.poll());
        assertNull(rb.poll());
    }

    @Test
    void isEmptyOnEmpty() {
        RingBuffer rb = new RingBuffer(4);
        assertTrue(rb.isEmpty());
        rb.offer(new PositionUpdate(1, 0, 0, 0));
        assertFalse(rb.isEmpty());
        rb.poll();
        assertTrue(rb.isEmpty());
    }

    @Test
    void multipleProducersDoNotLoseUpdates() throws Exception {
        RingBuffer rb = new RingBuffer(10000);
        int producerCount = 8;
        int updatesPerProducer = 1000;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(producerCount);
        java.util.concurrent.atomic.AtomicInteger totalProduced = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int p = 0; p < producerCount; p++) {
            int producerId = p;
            new Thread(() -> {
                for (int i = 0; i < updatesPerProducer; i++) {
                    long vehicleId = producerId * updatesPerProducer + i;
                    rb.offer(new PositionUpdate(vehicleId, i, i, i));
                    totalProduced.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        int consumed = 0;
        PositionUpdate update;
        while ((update = rb.poll()) != null) {
            consumed++;
        }
        assertEquals(totalProduced.get(), consumed);
    }

    @Test
    void concurrentOfferAndPollDoNotCorrupt() throws Exception {
        RingBuffer rb = new RingBuffer(5000);
        int producerCount = 4;
        int updatesPerProducer = 500;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(producerCount);
        java.util.concurrent.atomic.AtomicInteger totalProduced = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int p = 0; p < producerCount; p++) {
            int producerId = p;
            new Thread(() -> {
                for (int i = 0; i < updatesPerProducer; i++) {
                    long vehicleId = producerId * updatesPerProducer + i;
                    rb.offer(new PositionUpdate(vehicleId, i, i, i));
                    totalProduced.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        int consumed = 0;
        PositionUpdate update;
        while ((update = rb.poll()) != null) {
            assertNotNull(update);
            consumed++;
        }
        assertEquals(totalProduced.get(), consumed);
    }
}
