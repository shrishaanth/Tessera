package com.geotracker.index;

import com.geotracker.model.Position;
import com.geotracker.model.PositionUpdate;
import com.geotracker.util.RingBuffer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class IndexerThread extends Thread {
    private final RingBuffer<PositionUpdate> ringBuffer;
    private final CowQuadtree quadtree;
    private final HamtIndex hamt;
    private volatile boolean running = true;
    private final int maxDirty;
    private final long publishIntervalMs;
    private volatile SpatialSnapshot publishedSnapshot;

    private int dirtyCount = 0;
    private long lastPublishTime = System.currentTimeMillis();
    private volatile long updatesProcessed = 0;
    private volatile long lastPublishMillis = 0;

    // Queue latency: wall-clock time between an update's timestamp and the moment it is indexed.
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();
    private final AtomicLong latencyMaxMs = new AtomicLong();
    private final AtomicLong failedUpdates = new AtomicLong();

    private final AtomicReference<CountDownLatch> clearRequest = new AtomicReference<>();

    public record LatencyStats(long count, double meanMs, long maxMs) {}

    public IndexerThread(int shardId, RingBuffer<PositionUpdate> ringBuffer, CowQuadtree quadtree, HamtIndex hamt, int maxDirty, long publishIntervalMs) {
        this.ringBuffer = ringBuffer;
        this.quadtree = quadtree;
        this.hamt = hamt;
        this.maxDirty = maxDirty;
        this.publishIntervalMs = publishIntervalMs;
        setName("IndexerThread-" + shardId);
    }

    @Override
    public void run() {
        while (running) {
            CountDownLatch clear = clearRequest.getAndSet(null);
            if (clear != null) {
                clearNow();
                clear.countDown();
                continue;
            }

            PositionUpdate update = ringBuffer.poll();
            if (update == null) {
                if (dirtyCount > 0 && System.currentTimeMillis() - lastPublishTime >= publishIntervalMs) {
                    publish();
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    if (!running) break;
                }
                continue;
            }

            try {
                process(update);
                updatesProcessed++;
            } catch (RuntimeException e) {
                // One malformed update must not take the whole shard down.
                failedUpdates.incrementAndGet();
            }

            long now = System.currentTimeMillis();
            if (dirtyCount >= maxDirty || (now - lastPublishTime) >= publishIntervalMs) {
                publish();
            }
        }
    }

    private void process(PositionUpdate update) {
        long vehicleId = update.vehicleId();
        double newX = update.x();
        double newY = update.y();

        Position oldPos = hamt.get(vehicleId);
        if (oldPos != null) {
            quadtree.update(vehicleId, oldPos.x(), oldPos.y(), newX, newY);
        } else {
            quadtree.insert(vehicleId, newX, newY);
        }
        hamt.put(vehicleId, new Position(newX, newY, update.timestamp()));
        dirtyCount++;

        long latency = System.currentTimeMillis() - update.timestamp();
        if (latency >= 0) {
            latencySumMs.addAndGet(latency);
            latencyCount.incrementAndGet();
            latencyMaxMs.accumulateAndGet(latency, Math::max);
        }
    }

    private void publish() {
        quadtree.publish();
        hamt.publish();
        publishedSnapshot = new SpatialSnapshot(quadtree.snapshot(), hamt.snapshot());
        dirtyCount = 0;
        lastPublishTime = System.currentTimeMillis();
        lastPublishMillis = lastPublishTime;
    }

    private void clearNow() {
        while (ringBuffer.poll() != null) {
            // discard updates queued before the clear
        }
        quadtree.clear();
        hamt.clear();
        publish();
    }

    /**
     * Empties this shard's indexes and publishes an empty snapshot. Must only be called while no producer
     * is feeding the ring buffer (i.e. with the simulation stopped). Blocks until the indexer thread has
     * applied the clear, or the timeout expires.
     */
    public boolean clearAndWait(long timeoutMs) {
        if (!isAlive()) {
            synchronized (this) {
                clearNow();
            }
            return true;
        }
        CountDownLatch done = new CountDownLatch(1);
        clearRequest.set(done);
        try {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public SpatialSnapshot getPublishedSnapshot() {
        return publishedSnapshot;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public long getUpdatesProcessed() {
        return updatesProcessed;
    }

    public long getFailedUpdates() {
        return failedUpdates.get();
    }

    public int getQueueDepth() {
        return ringBuffer.size();
    }

    /** Wall-clock time of the most recent snapshot publication (0 if none yet). */
    public long getLastPublishMillis() {
        return lastPublishMillis;
    }

    /** Returns queue-latency statistics accumulated since the previous call and resets them. */
    public LatencyStats drainLatencyStats() {
        long count = latencyCount.getAndSet(0);
        long sum = latencySumMs.getAndSet(0);
        long max = latencyMaxMs.getAndSet(0);
        return new LatencyStats(count, count == 0 ? 0.0 : (double) sum / count, max);
    }
}
