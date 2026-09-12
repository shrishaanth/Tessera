package com.tessera.fleet.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tessera.fleet.durable.GeofenceEventRecord;
import com.tessera.fleet.durable.InMemoryDurableStore;
import com.tessera.fleet.geofence.GeofenceService;
import com.tessera.fleet.geofence.Site;
import com.tessera.fleet.geofence.SiteGeometry;
import com.tessera.fleet.reporting.ReportModels.DwellReport;
import com.tessera.fleet.reporting.ReportModels.Readiness;

class ReportingServiceTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = System.currentTimeMillis();

    private InMemoryDurableStore store;
    private GeofenceService geofence;
    private ReportingService reporting;

    // minCollectionDays=14, minSiteExits=20
    private static final ReportingProperties PROPS = new ReportingProperties(14, 20, false);

    @BeforeEach
    void setUp() {
        store = new InMemoryDurableStore();
        geofence = mock(GeofenceService.class);
        when(geofence.sites()).thenReturn(List.of(
                site("S1", "Acme Corp"), site("S2", "North Yard")));
        reporting = new ReportingService(store, geofence, PROPS);
    }

    private static Site site(String id, String name) {
        return new Site(id, name, null, SiteGeometry.fromRadius(42.356, -71.063, 120),
                42.356, -71.063, 120.0, null, 0L);
    }

    private void exit(String siteId, long at, int dwell) {
        store.saveGeofenceEvents(List.of(GeofenceEventRecord.exit("V", siteId, at, dwell)));
    }

    @Test
    void averageDwellPerSiteWithEnoughDataFlagAndOverallAverage() {
        long t = NOW - 2 * DAY;
        exit("S1", t, 300);
        exit("S1", t + 1000, 600);   // S1 avg 450, 2 visits (< 20 -> not enough)
        exit("S2", t, 1200);         // S2 avg 1200, 1 visit

        DwellReport r = reporting.dwell(new ReportFilter(NOW - 7 * DAY, NOW, null));

        assertThat(r.totalVisits()).isEqualTo(3);
        assertThat(r.overallAvgDwellSeconds()).isEqualTo(700.0); // (300+600+1200)/3
        assertThat(r.bySite()).extracting(ReportModels.SiteDwell::siteName)
                .containsExactly("Acme Corp", "North Yard");
        assertThat(r.bySite().get(0).avgDwellSeconds()).isEqualTo(450.0);
        assertThat(r.bySite().get(0).enoughData()).isFalse();
    }

    @Test
    void dwellFiltersBySiteId() {
        long t = NOW - 2 * DAY;
        exit("S1", t, 300);
        exit("S2", t, 900);

        assertThat(reporting.dwell(new ReportFilter(NOW - 7 * DAY, NOW, "S2")).totalVisits())
                .isEqualTo(1);
    }

    @Test
    void trendComparesToTheImmediatelyPrecedingEqualPeriod() {
        // current 7-day window: avg 600
        exit("S1", NOW - 2 * DAY, 500);
        exit("S1", NOW - 2 * DAY + 1, 700);
        // previous 7-day window: avg 300
        exit("S1", NOW - 9 * DAY, 300);
        exit("S1", NOW - 9 * DAY + 1, 300);

        DwellReport r = reporting.dwell(new ReportFilter(NOW - 7 * DAY, NOW, null));
        assertThat(r.overallAvgDwellSeconds()).isEqualTo(600.0);
        assertThat(r.trend().previousValue()).isEqualTo(300.0);
        assertThat(r.trend().direction()).isEqualTo("up");
        assertThat(r.trend().deltaValue()).isEqualTo(300.0);
    }

    @Test
    void readinessReportsReasonsUntilThresholdsAreMet() {
        Readiness empty = reporting.readiness();
        assertThat(empty.ready()).isFalse();
        assertThat(empty.reasons()).isNotEmpty();
        assertThat(empty.collectionDays()).isZero();

        // 20 days of history + 30 recorded site visits (EXIT events)
        for (int i = 0; i < 30; i++) {
            long at = NOW - (20 - i / 2) * DAY;
            exit("S1", at, 300 + i);
        }
        Readiness ready = reporting.readiness();
        assertThat(ready.siteExits()).isEqualTo(30);
        assertThat(ready.collectionDays()).isGreaterThanOrEqualTo(14);
        assertThat(ready.ready()).isTrue();
        assertThat(ready.reasons()).isEmpty();
    }

    @Test
    void defaultsToATrailing30DayWindowWhenNoDatesGiven() {
        exit("S1", NOW - 5 * DAY, 300);
        exit("S1", NOW - 45 * DAY, 300);
        DwellReport r = reporting.dwell(new ReportFilter(null, null, null));
        assertThat(r.totalVisits()).isEqualTo(1); // only the one inside 30 days
    }
}
