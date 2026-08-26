package com.geotracker;

import com.geotracker.dashboard.SwingDashboard;
import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.index.IndexerThread;
import com.geotracker.ingestion.IngestionServer;
import com.geotracker.ingestion.NettyIngestionServer;
import com.geotracker.ingestion.ShardRouter;
import com.geotracker.model.BoundingBox;
import com.geotracker.routing.RoadGraph;
import com.geotracker.simulator.SimulatorClient;
import com.geotracker.simulator.VehicleSimulator;
import com.geotracker.util.Config;

import com.geotracker.geofence.GeofenceEngine;
import com.geotracker.geofence.GeofenceEngine.Zone;
import com.geotracker.model.Position;
import com.geotracker.routing.AStarRouter;
import com.geotracker.routing.RoadGraph;

import java.util.List;

public class DemoMain {
    public static void main(String[] args) throws Exception {
        int shards = Config.SHARDS;
        int vehicleCount = Config.DEMO_VEHICLE_COUNT;
        boolean runSimulator = true;

        BoundingBox bounds = new BoundingBox(Config.MAP_MIN_X, Config.MAP_MIN_Y, Config.MAP_MAX_X, Config.MAP_MAX_Y);

        ShardRouter shardRouter = new ShardRouter(shards, Config.RING_BUFFER_SIZE);
        CowQuadtree[] quadtrees = new CowQuadtree[shards];
        HamtIndex[] hamts = new HamtIndex[shards];
        IndexerThread[] indexers = new IndexerThread[shards];

        for (int i = 0; i < shards; i++) {
            quadtrees[i] = new CowQuadtree(bounds);
            hamts[i] = new HamtIndex();
            indexers[i] = new IndexerThread(i, shardRouter.getRingBuffer(i), quadtrees[i], hamts[i], Config.PUBLISH_MAX_DIRTY, Config.PUBLISH_INTERVAL_MS);
            indexers[i].start();
        }

        boolean useNetty = true;
        for (String arg : args) {
            if (arg.equals("--plain")) useNetty = false;
        }

        final NettyIngestionServer nettyServer;
        final IngestionServer plainServer;
        final Object startupLock = new Object();
        final boolean[] startupSuccess = {false};
        final Exception[] startupError = {null};

        if (useNetty) {
            nettyServer = new NettyIngestionServer(Config.PORT, shardRouter);
            plainServer = null;
            Thread serverThread = new Thread(() -> {
                try {
                    nettyServer.start();
                    synchronized (startupLock) {
                        startupSuccess[0] = true;
                        startupLock.notifyAll();
                    }
                } catch (Exception e) {
                    synchronized (startupLock) {
                        startupError[0] = e;
                        startupLock.notifyAll();
                    }
                }
            });
            serverThread.start();

            synchronized (startupLock) {
                while (!startupSuccess[0] && startupError[0] == null) {
                    startupLock.wait(100);
                }
            }
            if (startupError[0] != null) {
                System.err.println("Failed to start Netty server: " + startupError[0].getMessage());
                startupError[0].printStackTrace();
                for (IndexerThread indexer : indexers) {
                    indexer.shutdown();
                }
                System.exit(1);
            }
        } else {
            nettyServer = null;
            plainServer = new IngestionServer(Config.PORT, shardRouter);
            Thread serverThread = new Thread(() -> {
                try {
                    plainServer.start();
                    synchronized (startupLock) {
                        startupSuccess[0] = true;
                        startupLock.notifyAll();
                    }
                } catch (Exception e) {
                    synchronized (startupLock) {
                        startupError[0] = e;
                        startupLock.notifyAll();
                    }
                }
            });
            serverThread.start();

            synchronized (startupLock) {
                while (!startupSuccess[0] && startupError[0] == null) {
                    startupLock.wait(100);
                }
            }
            if (startupError[0] != null) {
                System.err.println("Failed to start plain server: " + startupError[0].getMessage());
                startupError[0].printStackTrace();
                for (IndexerThread indexer : indexers) {
                    indexer.shutdown();
                }
                System.exit(1);
            }
        }
        Thread.sleep(200);

        RoadGraph graph = RoadGraph.builder()
                .addGrid(20, 20, 50.0)
                .build();
        AStarRouter router = new AStarRouter(graph);

        List<Zone> zones = List.of(
                new Zone("center", List.of(
                        new Position(400, 400, 0),
                        new Position(600, 400, 0),
                        new Position(600, 600, 0),
                        new Position(400, 600, 0)
                ), new BoundingBox(400, 400, 600, 600)),
                new Zone("airport", List.of(
                        new Position(800, 800, 0),
                        new Position(900, 800, 0),
                        new Position(900, 900, 0),
                        new Position(800, 900, 0)
                ), new BoundingBox(800, 800, 900, 900))
        );

        SwingDashboard dashboard = new SwingDashboard(quadtrees, hamts, indexers, bounds, graph);
        dashboard.setZones(zones);
        dashboard.setVisible(true);
        GeofenceEngine geofenceEngine = new GeofenceEngine(indexers, zones);

        SimulatorClient[] clientHolder = new SimulatorClient[1];
        if (runSimulator) {
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
                        e.printStackTrace();
                        break;
                    }
                }
            }).start();
        }

        Thread geofenceThread = new Thread(() -> {
            while (true) {
                try {
                    var events = geofenceEngine.check();
                    for (var event : events) {
                        System.out.println("ALERT: Vehicle " + event.vehicleId() + " " + event.type() + " zone " + event.zoneId());
                    }
                    Thread.sleep(500);
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
            }
        });
        geofenceThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            dashboard.stop();
            if (clientHolder[0] != null) clientHolder[0].close();
            if (nettyServer != null) nettyServer.stop();
            if (plainServer != null) plainServer.stop();
            for (IndexerThread indexer : indexers) {
                indexer.shutdown();
            }
        }));

        System.out.println("Demo started. Close the window to exit.");
    }
}
