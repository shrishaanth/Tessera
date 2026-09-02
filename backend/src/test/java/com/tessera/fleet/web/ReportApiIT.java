package com.tessera.fleet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.GeofenceEventRecord;
import com.tessera.fleet.durable.InMemoryDurableStore;
import com.tessera.fleet.durable.JobRecord;
import com.tessera.fleet.geofence.GeofenceService;
import com.tessera.fleet.geofence.SiteDefinition;
import com.tessera.fleet.geofence.SiteService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.support.AbstractRedisIntegrationTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tessera.simulator.vehicle-count=0",
        "tessera.ingest-poll-millis=3600000",
        "tessera.broadcast-millis=3600000",
        "tessera.geofence.debounce-seconds=5",
        "tessera.reporting.min-collection-days=14",
        "tessera.reporting.min-completed-jobs=10"
})
class ReportApiIT extends AbstractRedisIntegrationTest {

    private static final long DAY = 86_400_000L;

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DurableStore durableStore;
    @Autowired SiteService siteService;
    @Autowired GeofenceService geofenceService;
    @Autowired LiveFleetService liveFleet;

    private String siteId;

    private String url(String p) {
        return "http://localhost:" + port + p;
    }

    private HttpHeaders login() {
        ResponseEntity<Map> resp = rest.postForEntity(url("/api/auth/login"),
                Map.of("username", "ops", "password", "ops"), Map.class);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add(HttpHeaders.COOKIE, resp.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0]);
        return h;
    }

    @BeforeEach
    void reset() {
        ((InMemoryDurableStore) durableStore).clear();
        liveFleet.flushAll();
        siteService.list().forEach(s -> siteService.delete(s.id()));
        siteId = siteService.create(new SiteDefinition("Acme Corp", null, null,
                42.3560, -71.0635, 150.0, null)).id();
    }

    private void seedHistory(int days, int perDay) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (int d = days; d >= 1; d--) {
            long dayStart = now - d * DAY;
            for (int k = 0; k < perDay; k++) {
                long at = dayStart + k * 900_000L;
                boolean onTime = (n % 4) != 0; // 75%
                durableStore.saveJob(new JobRecord("JOB-H" + n, "North Loop", "addr",
                        42.0, -71.0, siteId, "V" + n, "Ada", "COMPLETED",
                        at - 3_600_000L, at - 1_800_000L, at, at + (onTime ? 0 : 600_000L), at));
                durableStore.saveGeofenceEvents(List.of(GeofenceEventRecord.exit(
                        "V" + n, siteId, at, 300 + (n % 10) * 60)));
                n++;
            }
        }
    }

    @Test
    void reportsRequireAuthentication() {
        assertThat(rest.getForEntity(url("/api/reports/on-time"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void readinessBecomesReadyOnceHistoryIsSufficient() {
        HttpHeaders auth = login();

        ResponseEntity<Map<String, Object>> empty = rest.exchange(url("/api/reports/readiness"),
                HttpMethod.GET, new HttpEntity<>(auth), new ParameterizedTypeReference<>() { });
        assertThat(empty.getBody().get("ready")).isEqualTo(false);
        assertThat((List<?>) empty.getBody().get("reasons")).isNotEmpty();

        seedHistory(20, 3); // 20 days, 60 completed jobs

        ResponseEntity<Map<String, Object>> ready = rest.exchange(url("/api/reports/readiness"),
                HttpMethod.GET, new HttpEntity<>(auth), new ParameterizedTypeReference<>() { });
        assertThat(ready.getBody().get("ready")).isEqualTo(true);
        assertThat(((Number) ready.getBody().get("completedJobs")).intValue()).isEqualTo(60);
    }

    @Test
    void onTimeAndDwellReportsAggregateTheHistory() {
        HttpHeaders auth = login();
        seedHistory(20, 3);

        ResponseEntity<Map<String, Object>> onTime = rest.exchange(
                url("/api/reports/on-time"), HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        assertThat(onTime.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) onTime.getBody().get("completed")).intValue()).isEqualTo(60);
        double pct = ((Number) onTime.getBody().get("onTimePct")).doubleValue();
        assertThat(pct).isBetween(70.0, 80.0);
        assertThat((List<?>) onTime.getBody().get("byWeek")).isNotEmpty();
        assertThat(onTime.getBody().get("provisional")).isEqualTo(false);

        ResponseEntity<Map<String, Object>> dwell = rest.exchange(
                url("/api/reports/dwell"), HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        assertThat(((Number) dwell.getBody().get("totalVisits")).intValue()).isEqualTo(60);
        List<Map<String, Object>> bySite = (List<Map<String, Object>>) dwell.getBody().get("bySite");
        assertThat(bySite).anySatisfy(s -> assertThat(s.get("siteName")).isEqualTo("Acme Corp"));
    }

    @Test
    void aRealAssignedJobCompletesWhenTheVehicleReachesTheSiteAndCountsInTheReport() {
        HttpHeaders auth = login();
        long now = System.currentTimeMillis();

        // A live vehicle near the site.
        liveFleet.applyReport(new PositionReport("CAR-7", "Ben", 42.3548, -71.0665, 0, 20, now));

        // Create + assign a job whose destination is inside the site.
        ResponseEntity<Map<String, Object>> created = rest.exchange(url("/api/jobs"),
                HttpMethod.POST, new HttpEntity<>(Map.of("route", "North Loop",
                        "destLatitude", 42.3560, "destLongitude", -71.0635), auth),
                new ParameterizedTypeReference<>() { });
        String jobId = (String) ((Map<?, ?>) created.getBody().get("job")).get("id");
        assertThat(((Map<?, ?>) created.getBody().get("job")).get("siteId")).isEqualTo(siteId);

        rest.exchange(url("/api/jobs/" + jobId + "/assign"), HttpMethod.POST,
                new HttpEntity<>(Map.of("vehicleId", "CAR-7"), auth),
                new ParameterizedTypeReference<Map<String, Object>>() { });

        // Drive the vehicle into the site, past the debounce. Use timestamps a
        // little in the past so the completion lands inside the report window.
        liveFleet.applyReport(new PositionReport("CAR-7", "Ben", 42.3560, -71.0635, 0, 15, now));
        geofenceService.onPosition("CAR-7", 42.3560, -71.0635, now - 20_000);
        geofenceService.onPosition("CAR-7", 42.3560, -71.0635, now - 10_000); // ENTER confirmed

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ResponseEntity<Map<String, Object>> job = rest.exchange(url("/api/jobs/" + jobId),
                    HttpMethod.GET, new HttpEntity<>(auth), new ParameterizedTypeReference<>() { });
            assertThat(job.getBody().get("status")).isEqualTo("COMPLETED");
        });

        ResponseEntity<Map<String, Object>> onTime = rest.exchange(
                url("/api/reports/on-time"), HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        assertThat(((Number) onTime.getBody().get("completed")).intValue()).isEqualTo(1);
    }
}
