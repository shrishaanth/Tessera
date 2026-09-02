package com.tessera.fleet.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tessera.fleet.support.TestFixtures;

class RoadGraphLoaderTest {

    @Test
    void tinyGraphHasExpectedForwardAndReverseTopology() {
        RoadGraph g = TestFixtures.tinyGraph();

        assertThat(g.nodeCount()).isEqualTo(4);
        assertThat(g.edgeCount()).isEqualTo(4);

        // node 0 has two out-edges: -> 1 and -> 2
        int s = g.fwdRangeStart(0);
        int e = g.fwdRangeEnd(0);
        assertThat(e - s).isEqualTo(2);

        // node 2 has two in-edges (from 1 and from 0)
        assertThat(g.revRangeEnd(2) - g.revRangeStart(2)).isEqualTo(2);
        // node 0 has no in-edges
        assertThat(g.revRangeEnd(0) - g.revRangeStart(0)).isZero();
    }

    @Test
    void nearestNodeSnapsToClosestCoordinate() {
        RoadGraph g = TestFixtures.tinyGraph();
        assertThat(g.nearestNode(0.001, 0.0)).isEqualTo(0);
        assertThat(g.nearestNode(0.0, 0.19)).isEqualTo(2);
        assertThat(g.nearestNode(0.049, 0.2)).isEqualTo(3);
    }

    @Test
    void realOsmGraphLoadsWithReasonableSize() {
        RoadGraph g = TestFixtures.realRoadGraph();
        assertThat(g.areaName()).contains("Boston");
        assertThat(g.nodeCount()).isGreaterThan(500);
        assertThat(g.edgeCount()).isGreaterThan(1000);
        // every node's forward range is well-formed
        for (int i = 0; i < g.nodeCount(); i++) {
            assertThat(g.fwdRangeStart(i)).isLessThanOrEqualTo(g.fwdRangeEnd(i));
        }
    }
}
