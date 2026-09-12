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
    void siteVisitFactsAreScopedToTheWindow() {
        store.saveGeofenceEvents(List.of(
                GeofenceEventRecord.exit("V1", "S1", 5_000, 300),
                GeofenceEventRecord.exit("V2", "S1", 50_000, 600),
                GeofenceEventRecord.enter("V3", "S1", 60_000))); // ENTER is not a visit
        assertThat(store.siteVisits(0, 100_000)).hasSize(2);
        assertThat(store.siteVisits(0, 10_000)).hasSize(1);
    }

    @Test
    void reportingWindowReportsExtentAndExitCount() {
        store.savePositions(List.of(new PositionRecord("V1", 42.0, -71.0, 0, 0, 5_000)));
        store.saveGeofenceEvents(List.of(
                GeofenceEventRecord.enter("V1", "S1", 3_000),
                GeofenceEventRecord.exit("V1", "S1", 8_000, 5)));

        var w = store.reportingWindow();
        assertThat(w.earliestEpochMs()).isEqualTo(3_000);
        assertThat(w.latestEpochMs()).isEqualTo(8_000);
        assertThat(w.siteExits()).isEqualTo(1);
    }

    @Test
    void isAlwaysHealthy() {
        assertThat(store.healthy()).isTrue();
    }
}
