package com.tessera.fleet.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tessera.fleet.durable.GeofenceEventRecord;
import com.tessera.fleet.durable.InMemoryDurableStore;
import com.tessera.fleet.durable.JobRecord;
import com.tessera.fleet.geofence.GeofenceService;
import com.tessera.fleet.geofence.Site;
import com.tessera.fleet.geofence.SiteGeometry;
import com.tessera.fleet.reporting.ReportModels.DwellReport;
import com.tessera.fleet.reporting.ReportModels.OnTimeReport;
import com.tessera.fleet.reporting.ReportModels.Readiness;

class ReportingServiceTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = System.currentTimeMillis();

    private InMemoryDurableStore store;
    private GeofenceService geofence;
    private ReportingService reporting;

    private static final ReportingProperties PROPS =
            new ReportingProperties(14, 50, 20, 300, false);

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

    /** completedAt=at, expected=at, actual=at+latenessMs (so on time iff latenessMs<=grace). */
    private void job(String id, String route, String driver, String siteId, long at, long latenessMs) {
        store.saveJob(new JobRecord(id, route, "addr", 42.0, -71.0, siteId, "V-" + id, driver,
                "COMPLETED", at - 3_600_000L, at - 1_800_000L, at, at + latenessMs, at));
    }

    private void exit(String siteId, long at, int dwell) {
        store.saveGeofenceEvents(List.of(GeofenceEventRecord.exit("V", siteId, at, dwell)));
    }

    @Test
    void onTimePercentageCountsArrivalsWithinTheGraceWindow() {
        long t = NOW - 3 * DAY;
        job("a", "North Loop", "Ada", "S1", t, 0);            // on time
        job("b", "North Loop", "Ada", "S1", t, 120_000);      // within 300s grace -> on time
        job("c", "North Loop", "Ben", "S1", t, 600_000);      // late
        job("d", "South Loop", "Ben", "S2", t, 900_000);      // late

        OnTimeReport r = reporting.onTime(new ReportFilter(NOW - 7 * DAY, NOW, null, null, null));

        assertThat(r.completed()).isEqualTo(4);
        assertThat(r.onTime()).isEqualTo(2);
        assertThat(r.onTimePct()).isEqualTo(50.0);
        assertThat(r.provisional()).isTrue(); // store has < 50 completed jobs
    }

    @Test
    void filtersByRouteDriverAndSite() {
        long t = NOW - 2 * DAY;
        job("a", "North Loop", "Ada", "S1", t, 0);
        job("b", "North Loop", "Ben", "S1", t, 600_000);
        job("c", "South Loop", "Ada", "S2", t, 0);

        assertThat(reporting.onTime(new ReportFilter(NOW - 7 * DAY, NOW, "North Loop", null, null))
                .completed()).isEqualTo(2);
        assertThat(reporting.onTime(new ReportFilter(NOW - 7 * DAY, NOW, null, "Ada", null))
                .completed()).isEqualTo(2);
        assertThat(reporting.onTime(new ReportFilter(NOW - 7 * DAY, NOW, null, null, "S2"))
                .completed()).isEqualTo(1);
    }

    @Test
    void trendComparesToTheImmediatelyPrecedingEqualPeriod() {
        // current 7-day window: 1 of 2 on time (50%)
        job("c1", "R", "D", "S1", NOW - 2 * DAY, 0);
        job("c2", "R", "D", "S1", NOW - 2 * DAY, 600_000);
        // previous 7-day window: 2 of 2 on time (100%)
        job("p1", "R", "D", "S1", NOW - 9 * DAY, 0);
        job("p2", "R", "D", "S1", NOW - 9 * DAY, 0);

        OnTimeReport r = reporting.onTime(new ReportFilter(NOW - 7 * DAY, NOW, null, null, null));
        assertThat(r.onTimePct()).isEqualTo(50.0);
        assertThat(r.trend().previousValue()).isEqualTo(100.0);
        assertThat(r.trend().direction()).isEqualTo("down");
        assertThat(r.trend().deltaValue()).isEqualTo(-50.0);
    }

    @Test
    void weeklyBreakdownBucketsByIsoWeek() {
        job("w1", "R", "D", "S1", NOW - 2 * DAY, 0);
        job("w2", "R", "D", "S1", NOW - 10 * DAY, 600_000);
        OnTimeReport r = reporting.onTime(new ReportFilter(NOW - 21 * DAY, NOW, null, null, null));
        assertThat(r.byWeek()).hasSizeBetween(2, 3);
        assertThat(r.byWeek().stream().mapToInt(ReportModels.WeekPoint::completed).sum()).isEqualTo(2);
    }

    @Test
    void averageDwellPerSiteWithEnoughDataFlagAndOverallAverage() {
        long t = NOW - 2 * DAY;
        exit("S1", t, 300);
        exit("S1", t + 1000, 600);   // S1 avg 450, 2 visits (< 20 -> not enough)
        exit("S2", t, 1200);         // S2 avg 1200, 1 visit

        DwellReport r = reporting.dwell(new ReportFilter(NOW - 7 * DAY, NOW, null, null, null));

        assertThat(r.totalVisits()).isEqualTo(3);
        assertThat(r.overallAvgDwellSeconds()).isEqualTo(700.0); // (300+600+1200)/3
        assertThat(r.bySite()).extracting(ReportModels.SiteDwell::siteName)
                .containsExactly("Acme Corp", "North Yard");
        assertThat(r.bySite().get(0).avgDwellSeconds()).isEqualTo(450.0);
        assertThat(r.bySite().get(0).enoughData()).isFalse();
    }

    @Test
    void readinessReportsReasonsUntilThresholdsAreMet() {
        Readiness empty = reporting.readiness();
        assertThat(empty.ready()).isFalse();
        assertThat(empty.reasons()).isNotEmpty();
        assertThat(empty.collectionDays()).isZero();

        // 20 days of history + 60 completed jobs
        for (int i = 0; i < 60; i++) {
            long at = NOW - (20 - i / 4) * DAY;
            job("j" + i, "R", "D", "S1", at, i % 3 == 0 ? 600_000 : 0);
        }
        Readiness ready = reporting.readiness();
        assertThat(ready.completedJobs()).isEqualTo(60);
        assertThat(ready.collectionDays()).isGreaterThanOrEqualTo(14);
        assertThat(ready.ready()).isTrue();
        assertThat(ready.reasons()).isEmpty();
    }

    @Test
    void defaultsToATrailing30DayWindowWhenNoDatesGiven() {
        job("recent", "R", "D", "S1", NOW - 5 * DAY, 0);
        job("old", "R", "D", "S1", NOW - 45 * DAY, 0);
        OnTimeReport r = reporting.onTime(new ReportFilter(null, null, null, null, null));
        assertThat(r.completed()).isEqualTo(1); // only the one inside 30 days
    }
}
