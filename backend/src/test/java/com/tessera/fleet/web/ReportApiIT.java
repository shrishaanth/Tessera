package com.tessera.fleet.web;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.tessera.fleet.geofence.SiteDefinition;
import com.tessera.fleet.geofence.SiteService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.support.AbstractRedisIntegrationTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tessera.ingest-poll-millis=3600000",
        "tessera.broadcast-millis=3600000",
        "tessera.geofence.debounce-seconds=5",
        "tessera.reporting.min-collection-days=14",
        "tessera.reporting.min-site-exits=20"
})
class ReportApiIT extends AbstractRedisIntegrationTest {

    private static final long DAY = 86_400_000L;

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DurableStore durableStore;
    @Autowired SiteService siteService;
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

    /** {@code days} × {@code perDay} synthetic site visits (geofence EXIT + dwell). */
    private void seedVisits(int days, int perDay) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (int d = days; d >= 1; d--) {
            long dayStart = now - d * DAY;
            for (int k = 0; k < perDay; k++) {
                durableStore.saveGeofenceEvents(List.of(GeofenceEventRecord.exit(
                        "V" + n, siteId, dayStart + k * 900_000L, 300 + (n % 10) * 60)));
                n++;
            }
        }
    }

    @Test
    void reportsRequireAuthentication() {
        assertThat(rest.getForEntity(url("/api/reports/dwell"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void readinessBecomesReadyOnceHistoryIsSufficient() {
        HttpHeaders auth = login();

        ResponseEntity<Map<String, Object>> empty = rest.exchange(url("/api/reports/readiness"),
                HttpMethod.GET, new HttpEntity<>(auth), new ParameterizedTypeReference<>() { });
        assertThat(empty.getBody().get("ready")).isEqualTo(false);
        assertThat((List<?>) empty.getBody().get("reasons")).isNotEmpty();

        seedVisits(20, 3); // 20 days, 60 recorded visits

        ResponseEntity<Map<String, Object>> ready = rest.exchange(url("/api/reports/readiness"),
                HttpMethod.GET, new HttpEntity<>(auth), new ParameterizedTypeReference<>() { });
        assertThat(ready.getBody().get("ready")).isEqualTo(true);
        assertThat(((Number) ready.getBody().get("siteExits")).intValue()).isEqualTo(60);
    }

    @Test
    void dwellReportAggregatesTheHistory() {
        HttpHeaders auth = login();
        seedVisits(20, 3);

        ResponseEntity<Map<String, Object>> dwell = rest.exchange(
                url("/api/reports/dwell"), HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        assertThat(dwell.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) dwell.getBody().get("totalVisits")).intValue()).isEqualTo(60);
        assertThat(dwell.getBody().get("provisional")).isEqualTo(false);
        List<Map<String, Object>> bySite = (List<Map<String, Object>>) dwell.getBody().get("bySite");
        assertThat(bySite).anySatisfy(s -> assertThat(s.get("siteName")).isEqualTo("Acme Corp"));
    }

    @Test
    void filterOptionsListTheSites() {
        HttpHeaders auth = login();
        ResponseEntity<Map<String, Object>> filters = rest.exchange(url("/api/reports/filters"),
                HttpMethod.GET, new HttpEntity<>(auth), new ParameterizedTypeReference<>() { });
        List<Map<String, Object>> sites = (List<Map<String, Object>>) filters.getBody().get("sites");
        assertThat(sites).anySatisfy(s -> assertThat(s.get("name")).isEqualTo("Acme Corp"));
    }
}
