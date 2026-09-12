package com.tessera.risk.common.road;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.geo.GeoMath;

/**
 * Guards the packaged OpenStreetMap road network. Everything downstream — segment
 * identity, speed-violation thresholds, the simulated trajectories — is derived
 * from this file, so a malformed or truncated graph must fail here rather than
 * surface as nonsense analytics later.
 */
class RoadNetworkLoaderTest {

    private static RoadNetwork network;

    @BeforeAll
    static void load() {
        network = RoadNetworkLoader.fromClasspath("roadgraph/roadgraph.json");
    }

    @Test
    void loadsTheRealDowntownNetwork() {
        assertThat(network.areaName()).isNotBlank();
        assertThat(network.nodeCount()).isGreaterThan(1000);
        assertThat(network.edgeCount()).isGreaterThan(1000);
    }

    @Test
    void everySegmentCarriesAUsableSpeedLimitAndLength() {
        for (int e = 0; e < network.edgeCount(); e++) {
            assertThat(network.speedLimitKph(e))
                    .as("segment %s speed limit", network.segmentId(e))
                    .isBetween(5.0, 130.0);
            assertThat(network.lengthM(e))
                    .as("segment %s length", network.segmentId(e))
                    .isGreaterThan(0.0);
            assertThat(network.travelSec(e)).isGreaterThan(0.0);
        }
    }

    @Test
    void segmentIdsAreUniqueAndStable() {
        assertThat(network.segmentId(0)).isEqualTo("SEG-00000");
        assertThat(network.segmentId(42)).isEqualTo("SEG-00042");
        long distinct = java.util.stream.IntStream.range(0, network.edgeCount())
                .mapToObj(network::segmentId).distinct().count();
        assertThat(distinct).isEqualTo(network.edgeCount());
    }

    @Test
    void forwardAndReverseIndicesAgreeOnEdgeCount() {
        int fwd = 0;
        int rev = 0;
        for (int n = 0; n < network.nodeCount(); n++) {
            fwd += network.fwdRangeEnd(n) - network.fwdRangeStart(n);
            rev += network.revRangeEnd(n) - network.revRangeStart(n);
        }
        assertThat(fwd).isEqualTo(network.edgeCount());
        assertThat(rev).isEqualTo(network.edgeCount());
    }

    @Test
    void edgeGeometryIsConsistentWithNodePositions() {
        // Declared length should agree with the great-circle distance between the
        // endpoints; OSM way geometry is split at every shape point, so these are
        // short straight segments and the two should match closely.
        for (int n = 0; n < network.nodeCount(); n++) {
            for (int e = network.fwdRangeStart(n); e < network.fwdRangeEnd(n); e++) {
                int to = network.fwdTarget(e);
                double geodesic = GeoMath.haversineMeters(
                        network.lat(n), network.lon(n), network.lat(to), network.lon(to));
                assertThat(network.lengthM(e))
                        .as("segment %s declared vs geodesic length", network.segmentId(e))
                        .isCloseTo(geodesic, org.assertj.core.data.Offset.offset(Math.max(5.0, geodesic * 0.2)));
            }
        }
    }

    @Test
    void nearestNodeFindsTheClosestPoint() {
        int node = network.nearestNode(network.lat(100), network.lon(100));
        assertThat(node).isEqualTo(100);
    }

    @Test
    void edgeBetweenResolvesAdjacencyBothWays() {
        int from = 0;
        int e = network.fwdRangeStart(from);
        int to = network.fwdTarget(e);
        assertThat(network.edgeBetween(from, to)).isEqualTo(e);
        assertThat(network.edgeBetween(from, -1)).isEqualTo(-1);
    }
}
