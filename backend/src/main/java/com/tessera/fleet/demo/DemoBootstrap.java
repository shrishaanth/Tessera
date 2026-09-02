package com.tessera.fleet.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.GeofenceEventRecord;
import com.tessera.fleet.durable.JobRecord;
import com.tessera.fleet.geofence.Site;
import com.tessera.fleet.geofence.SiteDefinition;
import com.tessera.fleet.geofence.SiteService;
import com.tessera.fleet.job.JobService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.Job;
import com.tessera.fleet.model.Vehicle;

/**
 * Development convenience only (profile {@code demo}): seed customer sites, assign
 * a few live jobs so the map shows every status colour and geofence events fire,
 * and back-fill ~5 weeks of synthetic completed-job and site-visit history so the
 * Phase 3 reporting dashboard actually renders. The synthetic history is flagged
 * (`tessera.reporting.synthetic-history=true`) so the UI says so (FR-4.4). Never
 * active in tests or production.
 */
@Component
@Profile("demo")
public class DemoBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoBootstrap.class);
    private static final long DAY = 86_400_000L;

    private static final String[] ROUTES = {"North Loop", "South Loop", "East Run"};
    private static final String[] DRIVERS = {
            "A. Okafor", "C. Delgado", "E. Haddad", "J. Meyer", "M. Fernandez", "R. Andersson"};

    private final LiveFleetService liveFleet;
    private final JobService jobService;
    private final SiteService siteService;
    private final DurableStore durableStore;
    private final double availableRatio;

    public DemoBootstrap(LiveFleetService liveFleet, JobService jobService,
                         SiteService siteService, DurableStore durableStore,
                         FleetProperties properties) {
        this.liveFleet = liveFleet;
        this.jobService = jobService;
        this.siteService = siteService;
        this.durableStore = durableStore;
        this.availableRatio = properties.simulator() != null
                ? properties.simulator().availableRatio() : 0.7;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::seed, "demo-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    private void seedSites() {
        if (!siteService.list().isEmpty()) {
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

    /** ~5 weeks of completed jobs + site visits, with on-time % trending upward. */
    private void backfillHistory() {
        List<Site> sites = siteService.list();
        if (sites.isEmpty()) {
            return;
        }
        Random rnd = new Random(20260902L);
        long now = System.currentTimeMillis();
        int days = 36;
        int jobs = 0;
        int visits = 0;

        for (int d = days; d >= 1; d--) {
            long dayStart = now - d * DAY;
            int week = (days - d) / 7; // 0..5, later weeks perform better
            double onTimeRate = Math.min(0.90, 0.66 + week * 0.05);
            int jobsToday = 12 + rnd.nextInt(7);

            for (int k = 0; k < jobsToday; k++) {
                Site site = sites.get(rnd.nextInt(sites.size()));
                String route = ROUTES[rnd.nextInt(ROUTES.length)];
                String driver = DRIVERS[rnd.nextInt(DRIVERS.length)];
                long createdAt = dayStart + (7 + rnd.nextInt(9)) * 3_600_000L + rnd.nextInt(3_600_000);
                long assignedAt = createdAt + (2 + rnd.nextInt(6)) * 60_000L;
                long etaMs = (8 + rnd.nextInt(18)) * 60_000L;
                long expected = assignedAt + etaMs;
                boolean onTime = rnd.nextDouble() < onTimeRate;
                long lateness = onTime ? -rnd.nextInt(4 * 60_000) : (2 + rnd.nextInt(18)) * 60_000L;
                long actual = expected + lateness;
                String id = "JOB-H" + (100000 + jobs);

                durableStore.saveJob(new JobRecord(id, route, site.name(),
                        site.centerLat() != null ? site.centerLat() : 42.356,
                        site.centerLon() != null ? site.centerLon() : -71.063,
                        site.id(), "SIM-H" + (k % 20), driver, "COMPLETED",
                        createdAt, assignedAt, expected, actual, actual));
                jobs++;

                // Each visit that produced the job: an EXIT with a dwell time.
                int dwell = 240 + rnd.nextInt(2100); // 4–39 min
                durableStore.saveGeofenceEvents(List.of(
                        GeofenceEventRecord.enter("SIM-H" + (k % 20), site.id(), actual),
                        GeofenceEventRecord.exit("SIM-H" + (k % 20), site.id(),
                                actual + dwell * 1000L, dwell)));
                visits++;
            }
        }
        log.info("Demo bootstrap: back-filled {} completed jobs and {} site visits "
                + "across {} days (synthetic history)", jobs, visits, days);
    }

    private void seed() {
        try {
            seedSites();
            backfillHistory();

            List<Vehicle> vehicles = new ArrayList<>();
            for (int i = 0; i < 40 && vehicles.size() < 4; i++) {
                Thread.sleep(500);
                vehicles = liveFleet.allVehicles();
            }
            if (vehicles.size() < 4) {
                log.info("Demo bootstrap: not enough vehicles appeared, skipping job assignment");
                return;
            }
            int toAssign = (int) Math.round(vehicles.size() * (1.0 - availableRatio));
            Random rnd = new Random(7);
            int assigned = 0;
            for (int i = 0; i < vehicles.size() && assigned < toAssign; i++) {
                Vehicle v = vehicles.get(i);
                double dLat = (rnd.nextDouble() - 0.5) * 0.012;
                double dLon = (rnd.nextDouble() - 0.5) * 0.016;
                Job job = jobService.create(ROUTES[assigned % ROUTES.length],
                        "Demo destination #" + (assigned + 1),
                        v.latitude() + dLat, v.longitude() + dLon);
                jobService.assign(job.id(), v.vehicleId());
                assigned++;
            }
            log.info("Demo bootstrap: created and assigned {} live jobs", assigned);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Demo bootstrap failed: {}", e.toString());
        }
    }
}
