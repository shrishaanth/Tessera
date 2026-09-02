package com.tessera.fleet.geofence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.tessera.fleet.alert.Alert;
import com.tessera.fleet.alert.AlertService;
import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.GeofenceEventRecord;
import com.tessera.fleet.durable.WriteBehindService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.model.VehicleStatus;
import com.tessera.fleet.support.AbstractRedisIntegrationTest;

/**
 * End-to-end geofencing on the real wiring (embedded Redis + in-memory durable
 * store, no Docker): a vehicle drives into a site, dwells, and drives out —
 * producing debounced ENTER/EXIT events with a dwell time (FR-3.2–3.4), an
 * ON_SITE live status (FR-1.1), and a dwell alert (FR-3.5).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "tessera.simulator.vehicle-count=0",
        "tessera.ingest-poll-millis=3600000",
        "tessera.broadcast-millis=3600000",
        "tessera.geofence.debounce-seconds=10",
        "tessera.geofence.default-dwell-alert-seconds=60"
})
class GeofenceFlowIT extends AbstractRedisIntegrationTest {

    private static final double SITE_LAT = 42.3560;
    private static final double SITE_LON = -71.0635;
    private static final double AWAY_LAT = 42.3700;

    @Autowired LiveFleetService liveFleet;
    @Autowired GeofenceService geofenceService;
    @Autowired SiteService siteService;
    @Autowired AlertService alertService;
    @Autowired DurableStore durableStore;
    @Autowired WriteBehindService writeBehind;

    private String siteId;

    @BeforeEach
    void setUp() {
        liveFleet.flushAll();
        alertService.clear();
        siteService.list().forEach(s -> siteService.delete(s.id()));
        siteId = siteService.create(new SiteDefinition("Acme Corp", "1 Industrial Way",
                null, SITE_LAT, SITE_LON, 150.0, null)).id();
    }

    private void report(String vehicle, double lat, double lon, long ts) {
        liveFleet.applyReport(new PositionReport(vehicle, vehicle + " driver", lat, lon, 0, 15, ts));
        geofenceService.onPosition(vehicle, lat, lon, ts);
    }

    @Test
    void driveInDwellAndDriveOutProducesEnterExitEventsDwellTimeAndAnAlert() {
        String v = "TRUCK-12";
        long t0 = System.currentTimeMillis();

        report(v, AWAY_LAT, SITE_LON, t0);               // outside
        report(v, SITE_LAT, SITE_LON, t0 + 1_000);       // first fix inside (crossing)
        report(v, SITE_LAT, SITE_LON, t0 + 12_000);      // past 10s debounce -> ENTER confirmed

        assertThat(liveFleet.getVehicle(v).status()).isEqualTo(VehicleStatus.ON_SITE);
        assertThat(liveFleet.getVehicle(v).onSiteId()).isEqualTo(siteId);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(durableStore.recentGeofenceEvents(v, siteId, 10))
                        .extracting(GeofenceEventRecord::type)
                        .containsExactly(GeofenceEventRecord.Type.ENTER));

        // Dwell past the 60s alert threshold.
        report(v, SITE_LAT, SITE_LON, t0 + 62_000);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(alertService.list(true))
                        .anyMatch(a -> a.type() == Alert.Type.DWELL_EXCEEDED
                                && siteId.equals(a.siteId())));

        // Drive out.
        report(v, AWAY_LAT, SITE_LON, t0 + 120_000);      // first fix outside
        report(v, AWAY_LAT, SITE_LON, t0 + 131_000);      // past debounce -> EXIT confirmed

        assertThat(liveFleet.getVehicle(v).status()).isNotEqualTo(VehicleStatus.ON_SITE);
        assertThat(writeBehind.awaitDrained(3000)).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            var events = durableStore.recentGeofenceEvents(v, siteId, 10);
            assertThat(events).extracting(GeofenceEventRecord::type)
                    .containsExactly(GeofenceEventRecord.Type.EXIT, GeofenceEventRecord.Type.ENTER);
            GeofenceEventRecord exit = events.get(0);
            assertThat(exit.dwellSeconds()).isNotNull();
            assertThat(exit.dwellSeconds()).isBetween(115, 125); // ~119s (t0+1s .. t0+120s)
        });
    }

    @Test
    void gpsJitterAtTheBoundaryProducesNoEvents() {
        String v = "TRUCK-13";
        long t0 = System.currentTimeMillis();
        report(v, AWAY_LAT, SITE_LON, t0);
        report(v, SITE_LAT, SITE_LON, t0 + 1_000);   // blip inside
        report(v, AWAY_LAT, SITE_LON, t0 + 4_000);   // back outside within the 10s debounce
        report(v, AWAY_LAT, SITE_LON, t0 + 20_000);

        assertThat(writeBehind.awaitDrained(2000)).isTrue();
        assertThat(durableStore.recentGeofenceEvents(v, null, 10)).isEmpty();
        assertThat(liveFleet.getVehicle(v).status()).isEqualTo(VehicleStatus.AVAILABLE);
    }
}
