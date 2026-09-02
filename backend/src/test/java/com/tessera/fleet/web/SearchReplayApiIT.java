package com.tessera.fleet.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.InMemoryDurableStore;
import com.tessera.fleet.durable.PositionRecord;
import com.tessera.fleet.geocoding.UrlFetcher;
import com.tessera.fleet.geofence.SiteDefinition;
import com.tessera.fleet.geofence.SiteService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.support.AbstractRedisIntegrationTest;

/**
 * FR-6 (address geocoding + fuzzy site search) and FR-5 (trajectory replay) over
 * the real HTTP surface. Nominatim is stubbed so the suite stays offline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tessera.simulator.vehicle-count=0",
        "tessera.ingest-poll-millis=3600000",
        "tessera.broadcast-millis=3600000"
})
class SearchReplayApiIT extends AbstractRedisIntegrationTest {

    private static final String STUB_JSON = """
        [{"lat":"42.35540","lon":"-71.06330",
          "display_name":"Boston Common, Boston, Massachusetts, USA",
          "category":"leisure","type":"park","importance":0.6}]
        """;

    @TestConfiguration
    static class StubGeocoder {
        @Bean
        @Primary
        UrlFetcher stubFetcher() {
            return (uri, headers, timeout) -> STUB_JSON;
        }
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SiteService siteService;
    @Autowired LiveFleetService liveFleet;
    @Autowired DurableStore durableStore;

    private String url(String p) {
        return "http://localhost:" + port + p;
    }

    private HttpHeaders login() {
        ResponseEntity<Map> resp = rest.postForEntity(url("/api/auth/login"),
                Map.of("username", "dispatch", "password", "dispatch"), Map.class);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add(HttpHeaders.COOKIE, resp.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0]);
        return h;
    }

    @BeforeEach
    void seed() {
        liveFleet.flushAll();
        siteService.list().forEach(s -> siteService.delete(s.id()));
        ((InMemoryDurableStore) durableStore).clear();
        siteService.create(new SiteDefinition("Common Depot", "Tremont St", null,
                42.3556, -71.0633, 140.0, null));
        siteService.create(new SiteDefinition("North Station Yard", "Causeway St", null,
                42.3659, -71.0611, 160.0, null));

        long now = System.currentTimeMillis();
        // A live vehicle (for the replay-vehicle list) with durable position history.
        liveFleet.applyReport(new PositionReport("CAR-1", "Ada", 42.355, -71.063, 90, 20, now));
        for (int i = 0; i < 30; i++) {
            durableStore.savePositions(List.of(new PositionRecord("CAR-1",
                    42.355 + i * 1e-4, -71.063 + i * 1e-4, 20, 90, now - (30 - i) * 60_000L)));
        }
    }

    @Test
    void geocodeRequiresAuthThenReturnsSuggestions() {
        assertThat(rest.getForEntity(url("/api/geocode?q=boston"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders auth = login();
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/geocode?q=boston%20common"), HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.getBody().get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("displayName")).asString().contains("Boston Common");
        assertThat(((Number) results.get(0).get("latitude")).doubleValue()).isEqualTo(42.3554);
        assertThat(resp.getBody().get("degraded")).isEqualTo(false);
    }

    @Test
    void fuzzySiteSearchFindsAKnownSiteDespiteATypo() {
        HttpHeaders auth = login();
        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/sites/search?q=comon%20depo"), HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).extracting(m -> m.get("name")).contains("Common Depot");
    }

    @Test
    void replayListsVehiclesAndReturnsATrajectory() {
        HttpHeaders auth = login();

        ResponseEntity<List<Map<String, Object>>> vehicles = rest.exchange(
                url("/api/replay/vehicles"), HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        assertThat(vehicles.getBody()).extracting(m -> m.get("vehicleId")).contains("CAR-1");

        long now = System.currentTimeMillis();
        ResponseEntity<Map<String, Object>> traj = rest.exchange(
                url("/api/replay/trajectory?vehicleId=CAR-1&from=" + (now - 3_600_000L)
                        + "&to=" + now),
                HttpMethod.GET, new HttpEntity<>(auth), new ParameterizedTypeReference<>() { });
        assertThat(traj.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) traj.getBody().get("totalPoints")).intValue()).isEqualTo(30);
        assertThat((List<?>) traj.getBody().get("points")).hasSize(30);
        assertThat(traj.getBody().get("sampled")).isEqualTo(false);
    }
}
