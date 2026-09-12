package com.tessera.fleet.demo;

import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.GeofenceEventRecord;
import com.tessera.fleet.geofence.Site;
import com.tessera.fleet.geofence.SiteDefinition;
import com.tessera.fleet.geofence.SiteService;

/**
 * Development convenience only (profile {@code demo}): seed a few customer sites
 * over the demo-area road network and back-fill synthetic site-visit history so
 * the average-dwell report (FR-4.2) renders before the live feed has run for
 * weeks. The synthetic history is flagged (`tessera.reporting.synthetic-history`)
 * so the UI says so (FR-4.4). Never active in tests or production.
 *
 * <p>Idempotent across restarts: nothing is re-seeded once the durable store
 * already holds sites / geofence history.
 */
@Component
@Profile("demo")
public class DemoBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoBootstrap.class);
    private static final long DAY = 86_400_000L;

    private final SiteService siteService;
    private final DurableStore durableStore;

    public DemoBootstrap(SiteService siteService, DurableStore durableStore) {
        this.siteService = siteService;
        this.durableStore = durableStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::seed, "demo-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    private void seed() {
        try {
            seedSites();

            // Idempotent: only back-fill into an empty geofence-event history.
            if (!durableStore.recentGeofenceEvents(null, null, 1).isEmpty()) {
                log.info("Demo bootstrap: geofence history already present — skipping back-fill");
                return;
            }
            backfillSiteVisits();
        } catch (Exception e) {
            log.warn("Demo bootstrap failed: {}", e.toString());
        }
    }

    private void seedSites() {
        // Check the durable store, not the live engine: this runs on a daemon
        // thread that can beat GeofenceService's startup reload.
        if (!durableStore.loadSites().isEmpty()) {
            return;
        }
        siteService.create(new SiteDefinition("Downtown Crossing Depot",
                "Washington St & Summer St", null, 42.35560, -71.06030, 150.0, null));
        siteService.create(new SiteDefinition("North Station Yard",
                "Causeway St", null, 42.36585, -71.06110, 160.0, 120));
        siteService.create(new SiteDefinition("Common Depot", "Tremont St", List.of(
                List.of(42.35520, -71.06490),
                List.of(42.35520, -71.06210),
                List.of(42.35700, -71.06210),
                List.of(42.35700, -71.06490)), null, null, null, 12));
        log.info("Demo bootstrap: seeded {} sites", siteService.list().size());
    }

    /** ~5 weeks of synthetic site visits (a geofence ENTER/EXIT pair with a dwell time). */
    private void backfillSiteVisits() {
        List<Site> sites = siteService.list();
        if (sites.isEmpty()) {
            return;
        }
        Random rnd = new Random(20260902L);
        long now = System.currentTimeMillis();
        int days = 36;
        int visits = 0;

        for (int d = days; d >= 1; d--) {
            long dayStart = now - d * DAY;
            int visitsToday = 12 + rnd.nextInt(7);
            for (int k = 0; k < visitsToday; k++) {
                Site site = sites.get(rnd.nextInt(sites.size()));
                String vehicleId = "DEMO-" + (k % 20);
                long enter = dayStart + (7 + rnd.nextInt(10)) * 3_600_000L + rnd.nextInt(3_600_000);
                int dwell = 240 + rnd.nextInt(2100); // 4–39 min
                durableStore.saveGeofenceEvents(List.of(
                        GeofenceEventRecord.enter(vehicleId, site.id(), enter),
                        GeofenceEventRecord.exit(vehicleId, site.id(), enter + dwell * 1000L, dwell)));
                visits++;
            }
        }
        log.info("Demo bootstrap: back-filled {} synthetic site visits across {} days",
                visits, days);
    }
}
