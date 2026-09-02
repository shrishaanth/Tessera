package com.tessera.fleet.demo;

import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.geofence.SiteDefinition;
import com.tessera.fleet.geofence.SiteService;
import com.tessera.fleet.job.JobService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.Job;
import com.tessera.fleet.model.Vehicle;

/**
 * Development convenience only (profile {@code demo}): seed a couple of customer
 * sites in the demo area and, once the live layer has vehicles, assign a few of
 * them jobs — so the dispatcher map shows every status colour (FR-1.1) and
 * geofence enters/exits fire without anyone having to click. Never active in
 * tests or production.
 */
@Component
@Profile("demo")
public class DemoBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoBootstrap.class);

    private final LiveFleetService liveFleet;
    private final JobService jobService;
    private final SiteService siteService;
    private final double availableRatio;

    public DemoBootstrap(LiveFleetService liveFleet, JobService jobService,
                         SiteService siteService, FleetProperties properties) {
        this.liveFleet = liveFleet;
        this.jobService = jobService;
        this.siteService = siteService;
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
        // Radius sites on busy points of the downtown-Boston demo road network.
        siteService.create(new SiteDefinition("Downtown Crossing Depot",
                "Washington St & Summer St", null, 42.35560, -71.06030, 150.0, null));
        siteService.create(new SiteDefinition("North Station Yard",
                "Causeway St", null, 42.36585, -71.06110, 160.0, 120));
        // A polygon site around Boston Common's north-east corner. Low dwell
        // threshold so a normal pass-through raises a demo alert (FR-3.5).
        siteService.create(new SiteDefinition("Common Depot", "Tremont St", List.of(
                List.of(42.35520, -71.06490),
                List.of(42.35520, -71.06210),
                List.of(42.35700, -71.06210),
                List.of(42.35700, -71.06490)), null, null, null, 12));
        log.info("Demo bootstrap: seeded {} sites", siteService.list().size());
    }

    private void seed() {
        try {
            seedSites();
            List<Vehicle> vehicles = List.of();
            for (int i = 0; i < 40 && vehicles.size() < 4; i++) {
                Thread.sleep(500);
                vehicles = liveFleet.allVehicles();
            }
            if (vehicles.size() < 4) {
                log.info("Demo bootstrap: not enough vehicles appeared, skipping");
                return;
            }
            int toAssign = (int) Math.round(vehicles.size() * (1.0 - availableRatio));
            Random rnd = new Random(7);
            int assigned = 0;
            for (int i = 0; i < vehicles.size() && assigned < toAssign; i++) {
                Vehicle v = vehicles.get(i);
                // A destination roughly 400–900 m away from the vehicle.
                double dLat = (rnd.nextDouble() - 0.5) * 0.012;
                double dLon = (rnd.nextDouble() - 0.5) * 0.016;
                Job job = jobService.create("Demo destination #" + (assigned + 1),
                        v.latitude() + dLat, v.longitude() + dLon);
                jobService.assign(job.id(), v.vehicleId());
                assigned++;
            }
            log.info("Demo bootstrap: created and assigned {} jobs", assigned);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Demo bootstrap failed: {}", e.toString());
        }
    }
}
