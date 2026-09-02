package com.tessera.fleet.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.model.StatusChange;
import com.tessera.fleet.model.Vehicle;
import com.tessera.fleet.model.VehicleStatus;
import com.tessera.fleet.support.AbstractRedisIntegrationTest;

@SpringBootTest
@TestPropertySource(properties = {
        "tessera.simulator.vehicle-count=0",
        "tessera.ingest-poll-millis=3600000",
        "tessera.broadcast-millis=3600000",
        "tessera.offline-after-seconds=1"
})
class LiveFleetServiceIT extends AbstractRedisIntegrationTest {

    @Autowired
    LiveFleetService liveFleet;

    private static PositionReport report(String id, double lat, double lon) {
        return new PositionReport(id, id + " driver", lat, lon, 0, 25, System.currentTimeMillis());
    }

    @BeforeEach
    void clean() {
        liveFleet.flushAll();
    }

    @Test
    void appliedReportsBecomeVisibleVehicles() {
        liveFleet.applyReport(report("IT-1", 42.3601, -71.0589));
        liveFleet.applyReport(report("IT-2", 42.3650, -71.0700));

        List<Vehicle> all = liveFleet.allVehicles();
        assertThat(all).extracting(Vehicle::vehicleId).containsExactly("IT-1", "IT-2");
        assertThat(liveFleet.getVehicle("IT-1").status()).isEqualTo(VehicleStatus.AVAILABLE);
        assertThat(liveFleet.getVehicle("IT-1").driverName()).isEqualTo("IT-1 driver");
    }

    @Test
    void geoSearchReturnsNearbyVehiclesNearestFirst() {
        liveFleet.applyReport(report("NEAR", 42.3603, -71.0587));
        liveFleet.applyReport(report("MID", 42.3630, -71.0620));
        liveFleet.applyReport(report("FAR", 42.3680, -71.0540));

        List<GeoCandidate> hits = liveFleet.searchNearby(42.3601, -71.0589, 5000, 10);

        assertThat(hits).extracting(GeoCandidate::vehicleId).containsExactly("NEAR", "MID", "FAR");
        assertThat(hits.get(0).straightLineMeters())
                .isLessThan(hits.get(1).straightLineMeters());
    }

    @Test
    void assigningAJobFlipsStatusToEnRouteAndRecordsHistory() {
        liveFleet.applyReport(report("IT-9", 42.36, -71.06));
        assertThat(liveFleet.getVehicle("IT-9").status()).isEqualTo(VehicleStatus.AVAILABLE);

        liveFleet.setCurrentJob("IT-9", "JOB-1");

        assertThat(liveFleet.getVehicle("IT-9").status()).isEqualTo(VehicleStatus.EN_ROUTE);
        assertThat(liveFleet.getVehicle("IT-9").currentJobId()).isEqualTo("JOB-1");
        assertThat(liveFleet.statusHistory("IT-9"))
                .extracting(StatusChange::status)
                .containsExactly(VehicleStatus.AVAILABLE, VehicleStatus.EN_ROUTE);
    }

    @Test
    void staleVehicleSweepsToOfflineInHistory() {
        liveFleet.applyReport(report("IT-OLD", 42.36, -71.06));

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            liveFleet.sweepStatusTransitions();
            assertThat(liveFleet.getVehicle("IT-OLD").status()).isEqualTo(VehicleStatus.OFFLINE);
        });
        assertThat(liveFleet.statusHistory("IT-OLD"))
                .extracting(StatusChange::status)
                .containsExactly(VehicleStatus.AVAILABLE, VehicleStatus.OFFLINE);
    }
}
