package com.geotracker;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.index.IndexerThread;
import com.geotracker.ingestion.IngestionServer;
import com.geotracker.ingestion.ShardRouter;
import com.geotracker.model.BoundingBox;
import com.geotracker.util.Config;

public class ServerMain {
    public static void main(String[] args) throws Exception {
        int port = Config.PORT;
        int shards = Config.SHARDS;
        int ringBufferSize = Config.RING_BUFFER_SIZE;
        int maxDirty = Config.PUBLISH_MAX_DIRTY;
        long publishIntervalMs = Config.PUBLISH_INTERVAL_MS;
        BoundingBox bounds = new BoundingBox(Config.MAP_MIN_X, Config.MAP_MIN_Y, Config.MAP_MAX_X, Config.MAP_MAX_Y);

        ShardRouter shardRouter = new ShardRouter(shards, ringBufferSize);
        IndexerThread[] indexers = new IndexerThread[shards];

        for (int i = 0; i < shards; i++) {
            CowQuadtree quadtree = new CowQuadtree(bounds);
            HamtIndex hamt = new HamtIndex();
            indexers[i] = new IndexerThread(i, shardRouter.getRingBuffer(i), quadtree, hamt, maxDirty, publishIntervalMs);
            indexers[i].start();
        }

        IngestionServer server = new IngestionServer(port, shardRouter);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.stop();
            for (IndexerThread indexer : indexers) {
                indexer.shutdown();
            }
        }));

        System.out.println("Server started on port " + port + " with " + shards + " shards");
    }
}
