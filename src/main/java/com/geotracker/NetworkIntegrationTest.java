package com.geotracker;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.index.IndexerThread;
import com.geotracker.ingestion.IngestionServer;
import com.geotracker.ingestion.ShardRouter;
import com.geotracker.model.BoundingBox;
import com.geotracker.routing.RoadGraph;
import com.geotracker.simulator.SimulatorClient;
import com.geotracker.simulator.VehicleSimulator;
import com.geotracker.util.Config;

import java.io.IOException;

public class NetworkIntegrationTest {
    public static void main(String[] args) throws Exception {
        int port = 19099;
        int shards = 2;
        int vehicleCount = 50;
        BoundingBox bounds = new BoundingBox(Config.MAP_MIN_X, Config.MAP_MIN_Y, Config.MAP_MAX_X, Config.MAP_MAX_Y);

        ShardRouter shardRouter = new ShardRouter(shards, Config.RING_BUFFER_SIZE);
        CowQuadtree[] quadtrees = new CowQuadtree[shards];
        HamtIndex[] hamts = new HamtIndex[shards];
        IndexerThread[] indexers = new IndexerThread[shards];

        for (int i = 0; i < shards; i++) {
            quadtrees[i] = new CowQuadtree(bounds);
            hamts[i] = new HamtIndex();
            indexers[i] = new IndexerThread(i, shardRouter.getRingBuffer(i), quadtrees[i], hamts[i], 100, 200);
            indexers[i].start();
        }

        IngestionServer server = new IngestionServer(port, shardRouter);
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        serverThread.start();

        Thread.sleep(200);

        RoadGraph graph = RoadGraph.builder()
                .addGrid(10, 10, 100.0)
                .build();

        VehicleSimulator simulator = new VehicleSimulator(graph, vehicleCount, 200.0, 0.1, Config.SEED);
        SimulatorClient client = new SimulatorClient("localhost", port);
        client.connect();

        System.out.println("Running network integration test: " + vehicleCount + " vehicles");

        for (int tick = 0; tick < 5; tick++) {
            var updates = simulator.tick();
            client.sendBatch(updates);
            Thread.sleep(100);
        }

        client.close();
        Thread.sleep(300);

        int totalPoints = 0;
        for (int i = 0; i < shards; i++) {
            var points = quadtrees[i].rangeQuery(bounds);
            totalPoints += points.size();
        }

        System.out.println("Total points across all shards: " + totalPoints);
        if (totalPoints == vehicleCount) {
            System.out.println("PASS: Network integration test - all vehicles indexed");
        } else {
            System.out.println("FAIL: Expected " + vehicleCount + " vehicles, got " + totalPoints);
        }

        server.stop();
        for (IndexerThread indexer : indexers) {
            indexer.shutdown();
        }
    }
}
