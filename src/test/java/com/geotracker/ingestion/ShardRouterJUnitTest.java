package com.geotracker.ingestion;

import com.geotracker.model.PositionUpdate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ShardRouterJUnitTest {

    @Test
    void routesToCorrectShard() {
        ShardRouter router = new ShardRouter(4, 1024);
        router.route(new PositionUpdate(4, 0, 0, 0));
        router.route(new PositionUpdate(5, 0, 0, 0));
        router.route(new PositionUpdate(6, 0, 0, 0));
        router.route(new PositionUpdate(7, 0, 0, 0));
        assertEquals(4, router.getRingBuffer(0).poll().vehicleId());
        assertEquals(5, router.getRingBuffer(1).poll().vehicleId());
        assertEquals(6, router.getRingBuffer(2).poll().vehicleId());
        assertEquals(7, router.getRingBuffer(3).poll().vehicleId());
    }

    @Test
    void negativeIdsRouteCorrectly() {
        ShardRouter router = new ShardRouter(4, 1024);
        router.route(new PositionUpdate(-1, 0, 0, 0));
        router.route(new PositionUpdate(Long.MIN_VALUE, 0, 0, 0));
        router.route(new PositionUpdate(Long.MAX_VALUE, 0, 0, 0));
        int shard0 = router.getRingBuffer(0).poll() != null ? 1 : 0;
        int shard1 = router.getRingBuffer(1).poll() != null ? 1 : 0;
        int shard2 = router.getRingBuffer(2).poll() != null ? 1 : 0;
        int shard3 = router.getRingBuffer(3).poll() != null ? 1 : 0;
        assertEquals(2, shard0 + shard1 + shard2 + shard3);
        assertTrue(shard0 == 1 || shard3 == 1);
    }
}
