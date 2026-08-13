package com.geotracker;

import com.geotracker.dashboard.SwingDashboard;
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

public class DemoMain {
    public static void main(String[] args) throws Exception {
        int shards = 2;
        int vehicleCount = 200;
        boolean runSimulator = true;

        BoundingBox bounds = new BoundingBox(Config.MAP_MIN_X, Config.MAP_MIN_Y, Config.MAP_MAX_X, Config.MAP_MAX_Y);

        ShardRouter shardRouter = new ShardRouter(shards, Config.RING_BUFFER_SIZE);
        CowQuadtree[] quadtrees = new CowQuadtree[shards];
        HamtIndex[] hamts = new HamtIndex[shards];
        IndexerThread[] indexers = new IndexerThread[shards];

        for (int i = 0; i < shards; i++) {
            quadtrees[i] = new CowQuadtree(bounds);
            hamts[i] = new HamtIndex();
            indexers[i] = new IndexerThread(i, shardRouter.getRingBuffer(i), quadtrees[i], hamts[i], 50, 50);
            indexers[i].start();
        }

        IngestionServer server = new IngestionServer(Config.PORT, shardRouter);
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.start();

        Thread.sleep(200);

        SwingDashboard dashboard = new SwingDashboard(quadtrees[0], hamts[0], bounds);
        dashboard.setVisible(true);

        SimulatorClient[] clientHolder = new SimulatorClient[1];
        if (runSimulator) {
            RoadGraph graph = RoadGraph.builder()
                    .addGrid(20, 20, 50.0)
                    .build();
            VehicleSimulator simulator = new VehicleSimulator(graph, vehicleCount, 200.0, 0.05, Config.SEED);
            SimulatorClient client = new SimulatorClient("localhost", Config.PORT);
            client.connect();
            clientHolder[0] = client;

            new Thread(() -> {
                while (true) {
                    try {
                        var updates = simulator.tick();
                        client.sendBatch(updates);
                        Thread.sleep(50);
                    } catch (Exception e) {
                        break;
                    }
                }
            }).start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            dashboard.stop();
            if (clientHolder[0] != null) clientHolder[0].close();
            server.stop();
            for (IndexerThread indexer : indexers) {
                indexer.shutdown();
            }
        }));

        System.out.println("Demo started. Close the window to exit.");
    }
}
