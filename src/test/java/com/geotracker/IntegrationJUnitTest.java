package com.geotracker;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.index.IndexerThread;
import com.geotracker.ingestion.ShardRouter;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.PositionUpdate;
import com.geotracker.routing.RoadGraph;
import com.geotracker.simulator.VehicleSimulator;
import com.geotracker.util.Config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class IntegrationJUnitTest {

    @Test
    void allVehiclesIndexedAcrossShards() throws Exception {
        int shards = 2;
        int vehicleCount = 100;
        BoundingBox bounds = new BoundingBox(Config.MAP_MIN_X, Config.MAP_MIN_Y, Config.MAP_MAX_X, Config.MAP_MAX_Y);

        ShardRouter shardRouter = new ShardRouter(shards, Config.RING_BUFFER_SIZE);
        CowQuadtree[] quadtrees = new CowQuadtree[shards];
        HamtIndex[] hamts = new HamtIndex[shards];
        IndexerThread[] indexers = new IndexerThread[shards];

        for (int i = 0; i < shards; i++) {
            quadtrees[i] = new CowQuadtree(bounds);
            hamts[i] = new HamtIndex();
            indexers[i] = new IndexerThread(i, shardRouter.getRingBuffer(i), quadtrees[i], hamts[i], 10, 100);
            indexers[i].start();
        }

        RoadGraph graph = RoadGraph.builder()
                .addGrid(20, 20, 50.0)
                .build();

        VehicleSimulator simulator = new VehicleSimulator(graph, vehicleCount, 50.0, 0.1, Config.SEED);

        for (int tick = 0; tick < 10; tick++) {
            var updates = simulator.tick();
            for (PositionUpdate update : updates) {
                shardRouter.route(update);
            }
            Thread.sleep(50);
        }

        Thread.sleep(300);

        int totalPoints = 0;
        for (int i = 0; i < shards; i++) {
            var points = quadtrees[i].rangeQuery(bounds);
            totalPoints += points.size();
        }

        assertEquals(vehicleCount, totalPoints,
                "Expected " + vehicleCount + " vehicles, got " + totalPoints);

        for (int i = 0; i < shards; i++) {
            indexers[i].shutdown();
        }
    }
}
