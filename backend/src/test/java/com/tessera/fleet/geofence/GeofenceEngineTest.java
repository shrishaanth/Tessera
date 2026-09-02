package com.tessera.fleet.geofence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tessera.fleet.durable.GeofenceEventRecord;

/**
 * The geofence state machine: debounced enter/exit (FR-3.2, FR-3.4), dwell
 * computation (FR-3.3) and dwell alerts (FR-3.5).
 */
class GeofenceEngineTest {

    private static final long DEBOUNCE_MS = 20_000;
    private static final long DWELL_ALERT_MS = 120_000;

    // ~300 m square around a point in the demo area.
    private static final Site SITE = new Site("SITE-A", "Depot A", null,
            SiteGeometry.fromRadius(42.3560, -71.0635, 150),
            42.3560, -71.0635, 150.0, null, 0L);

    private GeofenceEngine engine;

    @BeforeEach
    void setUp() {
        engine = new GeofenceEngine(DEBOUNCE_MS, DWELL_ALERT_MS);
        engine.reload(List.of(SITE));
    }

    private GeofenceEngine.Result inside(String v, long t) {
        return engine.evaluate(v, 42.3560, -71.0635, t);
    }

    private GeofenceEngine.Result outside(String v, long t) {
        return engine.evaluate(v, 42.3700, -71.0635, t);
    }

    @Test
    void noEnterEventUntilTheDebounceWindowHasElapsed() {
        assertThat(inside("V1", 0).events()).isEmpty();          // first fix inside
        assertThat(inside("V1", 10_000).events()).isEmpty();     // still within debounce
        GeofenceEngine.Result confirmed = inside("V1", 21_000);  // past debounce
        assertThat(confirmed.events()).hasSize(1);
        assertThat(confirmed.events().get(0).type()).isEqualTo(GeofenceEventRecord.Type.ENTER);
        assertThat(confirmed.events().get(0).epochMillis()).isEqualTo(0); // crossing moment, not confirmation
        assertThat(confirmed.currentSiteId()).isEqualTo("SITE-A");
    }

    @Test
    void aBoundaryFlickerWithinTheDebounceWindowProducesNoEvent() {
        inside("V2", 0);
        inside("V2", 5_000);
        GeofenceEngine.Result back = outside("V2", 8_000); // left before debounce elapsed
        assertThat(back.events()).isEmpty();
        assertThat(back.currentSiteId()).isNull();
        // and a later genuine entry still works
        inside("V2", 100_000);
        assertThat(inside("V2", 130_000).events()).extracting(GeofenceEventRecord::type)
                .containsExactly(GeofenceEventRecord.Type.ENTER);
    }

    @Test
    void exitIsDebouncedAndDwellTimeIsEntryToExit() {
        inside("V3", 0);
        inside("V3", 30_000); // ENTER confirmed here (enterMs = 0)

        assertThat(outside("V3", 600_000).events()).isEmpty();      // exiting, within debounce
        GeofenceEngine.Result exit = outside("V3", 621_000);        // exit confirmed
        assertThat(exit.events()).hasSize(1);
        GeofenceEventRecord e = exit.events().get(0);
        assertThat(e.type()).isEqualTo(GeofenceEventRecord.Type.EXIT);
        assertThat(e.epochMillis()).isEqualTo(600_000);             // first fix outside
        assertThat(e.dwellSeconds()).isEqualTo(600);                // 600_000 ms - 0
        assertThat(exit.currentSiteId()).isNull();
    }

    @Test
    void anExitFlickerWithinDebounceKeepsTheVehicleOnSite() {
        inside("V4", 0);
        inside("V4", 30_000);
        outside("V4", 40_000);                       // starts exiting
        GeofenceEngine.Result stay = inside("V4", 45_000); // came back before debounce
        assertThat(stay.events()).isEmpty();
        assertThat(stay.currentSiteId()).isEqualTo("SITE-A");
    }

    @Test
    void dwellAlertFiresOncePerVisitAfterTheThreshold() {
        inside("V5", 0);
        inside("V5", 30_000); // ENTER (enterMs = 0)

        assertThat(inside("V5", 100_000).dwellAlerts()).isEmpty();          // under 120s
        GeofenceEngine.Result alert = inside("V5", 121_000);
        assertThat(alert.dwellAlerts()).hasSize(1);
        assertThat(alert.dwellAlerts().get(0).siteId()).isEqualTo("SITE-A");
        assertThat(alert.dwellAlerts().get(0).dwellSeconds()).isGreaterThanOrEqualTo(120);

        assertThat(inside("V5", 200_000).dwellAlerts()).isEmpty();          // not repeated
    }

    @Test
    void reloadDropsVisitStateForRemovedSites() {
        inside("V6", 0);
        inside("V6", 30_000); // on site A
        assertThat(engine.evaluate("V6", 42.3560, -71.0635, 40_000).currentSiteId())
                .isEqualTo("SITE-A");

        engine.reload(List.of()); // site removed
        GeofenceEngine.Result after = engine.evaluate("V6", 42.3560, -71.0635, 50_000);
        assertThat(after.currentSiteId()).isNull();
        assertThat(after.events()).isEmpty();
    }
}
