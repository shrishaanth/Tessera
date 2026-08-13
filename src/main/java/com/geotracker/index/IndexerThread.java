package com.geotracker.index;

import com.geotracker.model.Position;
import com.geotracker.model.PositionUpdate;
import com.geotracker.util.RingBuffer;

public class IndexerThread extends Thread {
    private final RingBuffer ringBuffer;
    private final CowQuadtree quadtree;
    private final HamtIndex hamt;
    private volatile boolean running = true;
    private final int maxDirty;
    private final long publishIntervalMs;

    private int dirtyCount = 0;
    private long lastPublishTime = System.currentTimeMillis();
    private long updatesProcessed = 0;

    public IndexerThread(int shardId, RingBuffer ringBuffer, CowQuadtree quadtree, HamtIndex hamt, int maxDirty, long publishIntervalMs) {
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
            PositionUpdate update = ringBuffer.poll();
            if (update == null) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            process(update);
            updatesProcessed++;

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
    }

    private void publish() {
        quadtree.publish();
        hamt.publish();
        dirtyCount = 0;
        lastPublishTime = System.currentTimeMillis();
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    public long getUpdatesProcessed() {
        return updatesProcessed;
    }
}
