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
    void searchSitesRanksFuzzyNameMatches() {
        store.saveSite(new SiteRecord("S1", "North Station Yard", "Causeway St",
                "POLYGON((0 0,0 1,1 1,0 0))", null, null, null, null, 1));
        store.saveSite(new SiteRecord("S2", "Downtown Crossing Depot", "Washington St",
                "POLYGON((0 0,0 1,1 1,0 0))", null, null, null, null, 1));
        store.saveSite(new SiteRecord("S3", "Common Depot", "Tremont St",
                "POLYGON((0 0,0 1,1 1,0 0))", null, null, null, null, 1));

        assertThat(store.searchSites("north stn", 5)).extracting(SiteRecord::siteId)
                .containsExactly("S1");
        assertThat(store.searchSites("depot", 5)).extracting(SiteRecord::siteId)
                .containsExactlyInAnyOrder("S2", "S3");
        assertThat(store.searchSites("causeway", 5)).extracting(SiteRecord::siteId)
                .contains("S1"); // matched via address
        assertThat(store.searchSites("xyzzy", 5)).isEmpty();
    }

    @Test
    void trajectoryReturnsAVehiclesPositionsInTimeOrder() {
        store.savePositions(List.of(
                new PositionRecord("CAR-1", 42.0, -71.0, 0, 0, 3000),
                new PositionRecord("CAR-1", 42.1, -71.1, 0, 0, 1000),
                new PositionRecord("CAR-2", 1, 1, 0, 0, 2000),
                new PositionRecord("CAR-1", 42.2, -71.2, 0, 0, 9000)));

        assertThat(store.trajectory("CAR-1", 0, 5000))
                .extracting(PositionRecord::epochMillis).containsExactly(1000L, 3000L);
        assertThat(store.trajectory("CAR-1", 0, 100000))
                .extracting(PositionRecord::epochMillis).containsExactly(1000L, 3000L, 9000L);
    }

    @Test
    void jobsSaveAndLoad() {
        store.saveJob(new JobRecord("JOB-1", "North Loop", "addr", 42.0, -71.0, "S1",
                null, null, "UNASSIGNED", 1000, null, null, null, null));
        store.saveJob(new JobRecord("JOB-1", "North Loop", "addr", 42.0, -71.0, "S1",
                "CAR-2", "Driver A", "ASSIGNED", 1000, 2000L, 3000L, null, null));

        List<JobRecord> jobs = store.loadJobs();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).status()).isEqualTo("ASSIGNED");
        assertThat(jobs.get(0).assignedVehicleId()).isEqualTo("CAR-2");
        assertThat(jobs.get(0).driverName()).isEqualTo("Driver A");
    }

    @Test
    void completedJobFactsAndSiteVisitsAreScopedToTheWindow() {
        store.saveJob(new JobRecord("JOB-A", "R1", "a", 42.0, -71.0, "S1", "V1", "D1",
                "COMPLETED", 1_000, 2_000L, 10_000L, 9_000L, 9_000L));   // on time (9k <= 10k)
        store.saveJob(new JobRecord("JOB-B", "R1", "b", 42.0, -71.0, "S1", "V2", "D2",
                "COMPLETED", 1_000, 2_000L, 10_000L, 20_000L, 20_000L)); // late
        store.saveJob(new JobRecord("JOB-C", "R1", "c", 42.0, -71.0, "S1", "V3", "D3",
                "ASSIGNED", 1_000, 2_000L, 10_000L, null, null));        // not completed

        assertThat(store.completedJobs(0, 100_000)).extracting(f -> f.jobId())
                .containsExactlyInAnyOrder("JOB-A", "JOB-B");
        assertThat(store.completedJobs(0, 10_000)).extracting(f -> f.jobId())
                .containsExactly("JOB-A");
        assertThat(store.completedJobs(0, 100_000).stream()
                .filter(f -> f.onTime(0)).count()).isEqualTo(1);

        store.saveGeofenceEvents(List.of(
                GeofenceEventRecord.exit("V1", "S1", 5_000, 300),
                GeofenceEventRecord.exit("V2", "S1", 50_000, 600),
                GeofenceEventRecord.enter("V3", "S1", 60_000)));
        assertThat(store.siteVisits(0, 100_000)).hasSize(2);
        assertThat(store.siteVisits(0, 10_000)).hasSize(1);
    }

    @Test
    void reportingWindowReportsExtentAndCounts() {
        store.savePositions(List.of(new PositionRecord("V1", 42.0, -71.0, 0, 0, 5_000)));
        store.saveGeofenceEvents(List.of(
                GeofenceEventRecord.enter("V1", "S1", 3_000),
                GeofenceEventRecord.exit("V1", "S1", 8_000, 5)));
        store.saveJob(new JobRecord("JOB-A", null, "a", 42.0, -71.0, "S1", "V1", "D1",
                "COMPLETED", 1_000, 2_000L, 10_000L, 9_000L, 9_000L));

        var w = store.reportingWindow();
        assertThat(w.earliestEpochMs()).isEqualTo(3_000);
        assertThat(w.latestEpochMs()).isEqualTo(9_000); // completed job at 9_000
        assertThat(w.completedJobs()).isEqualTo(1);
        assertThat(w.siteExits()).isEqualTo(1);
    }

    @Test
    void isAlwaysHealthy() {
        assertThat(store.healthy()).isTrue();
    }
}
