package com.tessera.fleet.durable;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class InMemoryDurableStoreTest {

    private final InMemoryDurableStore store = new InMemoryDurableStore();

    @Test
    void countsPositionsAndKeepsGeofenceEvents() {
        store.savePositions(List.of(
                new PositionRecord("V1", 42.36, -71.06, 10, 0, 1000),
                new PositionRecord("V1", 42.36, -71.06, 10, 0, 2000)));
        store.saveGeofenceEvents(List.of(GeofenceEventRecord.enter("V1", "S1", 1500)));

        assertThat(store.positionCount()).isEqualTo(2);
        assertThat(store.recentGeofenceEvents(null, null, 10)).hasSize(1);
    }

    @Test
    void geofenceEventQueryFiltersByVehicleAndSiteNewestFirst() {
        store.saveGeofenceEvents(List.of(
                GeofenceEventRecord.enter("V1", "S1", 1000),
                GeofenceEventRecord.exit("V1", "S1", 2000, 1),
                GeofenceEventRecord.enter("V2", "S1", 3000),
                GeofenceEventRecord.enter("V1", "S2", 4000)));

        assertThat(store.recentGeofenceEvents("V1", null, 10))
                .extracting(GeofenceEventRecord::epochMillis)
                .containsExactly(4000L, 2000L, 1000L);
        assertThat(store.recentGeofenceEvents("V1", "S1", 10)).hasSize(2);
        assertThat(store.lastGeofenceEvent("V1", "S1")).get()
                .extracting(GeofenceEventRecord::type).isEqualTo(GeofenceEventRecord.Type.EXIT);
    }

    @Test
    void siteCrud() {
        SiteRecord a = new SiteRecord("S1", "A", "addr", "POLYGON((0 0,0 1,1 1,0 0))",
                null, null, null, null, 100);
        store.saveSite(a);
        assertThat(store.loadSites()).extracting(SiteRecord::siteId).containsExactly("S1");

        store.saveSite(new SiteRecord("S1", "A renamed", "addr", a.boundaryWkt(),
                null, null, null, 300, 100));
        assertThat(store.loadSites().get(0).name()).isEqualTo("A renamed");
        assertThat(store.loadSites().get(0).dwellAlertSeconds()).isEqualTo(300);

        store.deleteSite("S1");
        assertThat(store.loadSites()).isEmpty();
    }

    @Test
    void jobsSaveAndLoad() {
        store.saveJob(new JobRecord("JOB-1", "addr", 42.0, -71.0, null,
                "UNASSIGNED", 1000, null, null));
        store.saveJob(new JobRecord("JOB-1", "addr", 42.0, -71.0, "CAR-2",
                "ASSIGNED", 1000, 2000L, null));

        List<JobRecord> jobs = store.loadJobs();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).status()).isEqualTo("ASSIGNED");
        assertThat(jobs.get(0).assignedVehicleId()).isEqualTo("CAR-2");
    }

    @Test
    void isAlwaysHealthy() {
        assertThat(store.healthy()).isTrue();
    }
}
