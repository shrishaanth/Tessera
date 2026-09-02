package com.tessera.fleet.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tessera.fleet.support.TestFixtures;

class TravelTimeServiceTest {

    @Test
    void driveTimesToJobPrefersTheFasterTwoHopPathOverTheSlowDirectEdge() {
        TravelTimeService svc = new TravelTimeService(TestFixtures.tinyGraph());

        // Job located at node 2. Reverse-Dijkstra gives drive time INTO node 2.
        double[] toJob = svc.driveTimesToJob(2);

        assertThat(toJob[2]).isZero();
        assertThat(toJob[1]).isEqualTo(10.0);          // 1 -> 2
        assertThat(toJob[0]).isEqualTo(20.0);          // 0 -> 1 -> 2  beats  0 -> 2 (30s)
        assertThat(toJob[3]).isInfinite();             // 3 cannot reach 2
    }

    @Test
    void travelSecondsBetweenAddsSnapPenaltiesButKeepsOrdering() {
        TravelTimeService svc = new TravelTimeService(TestFixtures.tinyGraph());
        double near = svc.travelSecondsBetween(0.0, 0.101, 0.0, 0.2); // ~ node 1 -> node 2
        double far = svc.travelSecondsBetween(0.0, 0.001, 0.0, 0.2);  // ~ node 0 -> node 2
        assertThat(near).isLessThan(far);
        assertThat(near).isGreaterThanOrEqualTo(10.0);
    }

    @Test
    void realGraphRoutingCompletesWellWithinBudget() {
        TravelTimeService svc = new TravelTimeService(TestFixtures.realRoadGraph());
        RoadGraph g = svc.graph();
        int mid = g.nodeCount() / 2;

        long start = System.nanoTime();
        double[] times = svc.driveTimesToJob(mid);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(times[mid]).isZero();
        long reachable = java.util.Arrays.stream(times).filter(Double::isFinite).count();
        assertThat(reachable).isGreaterThan(g.nodeCount() / 4L);
        assertThat(elapsedMs).isLessThan(200L); // FR-2.3 budget is 1000ms for the whole request
    }
}
