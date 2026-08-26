package com.geotracker.ingestion;

import com.geotracker.model.PositionUpdate;
import com.geotracker.util.RingBuffer;

public class ShardRouter {
    private final RingBuffer[] ringBuffers;
    private final int shardCount;

    public ShardRouter(int shardCount, int ringBufferSize) {
        this.shardCount = shardCount;
        this.ringBuffers = new RingBuffer[shardCount];
        for (int i = 0; i < shardCount; i++) {
            ringBuffers[i] = new RingBuffer(ringBufferSize);
        }
    }

    public void route(PositionUpdate update) {
        int shard = (int) Math.floorMod(update.vehicleId(), shardCount);
        ringBuffers[shard].offer(update);
    }

    public RingBuffer getRingBuffer(int shard) {
        return ringBuffers[shard];
    }

    public int getShardCount() {
        return shardCount;
    }
}
