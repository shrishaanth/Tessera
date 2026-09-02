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

import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.support.AbstractRedisIntegrationTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tessera.simulator.vehicle-count=0",
        "tessera.ingest-poll-millis=3600000",
        "tessera.broadcast-millis=3600000"
})
class DispatchApiIT extends AbstractRedisIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    LiveFleetService liveFleet;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @BeforeEach
    void seedFleet() {
        liveFleet.flushAll();
        long now = System.currentTimeMillis();
        // A cluster of available vehicles around the Boston Common demo area.
        liveFleet.applyReport(new PositionReport("CAR-1", "Ada", 42.3559, -71.0640, 0, 20, now));
        liveFleet.applyReport(new PositionReport("CAR-2", "Ben", 42.3601, -71.0589, 0, 20, now));
        liveFleet.applyReport(new PositionReport("CAR-3", "Cy", 42.3520, -71.0710, 0, 20, now));
        liveFleet.applyReport(new PositionReport("CAR-4", "Di", 42.3675, -71.0550, 0, 20, now));
    }

    private HttpHeaders login(String user, String pass) {
        ResponseEntity<Map> resp = rest.postForEntity(url("/api/auth/login"),
                Map.of("username", user, "password", pass), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, cookies.get(0).split(";", 2)[0]);
        return headers;
    }

    @Test
    void protectedEndpointsRejectAnonymousCallers() {
        ResponseEntity<String> resp = rest.getForEntity(url("/api/vehicles"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void badCredentialsAreRejected() {
        ResponseEntity<Map> resp = rest.postForEntity(url("/api/auth/login"),
                Map.of("username", "dispatch", "password", "wrong"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authenticatedDispatcherSeesTheWholeFleetAndCanFilterByStatus() {
        HttpHeaders auth = login("dispatch", "dispatch");

        ResponseEntity<List<Map<String, Object>>> all = rest.exchange(url("/api/vehicles"),
                HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(all.getBody()).hasSize(4);

        ResponseEntity<List<Map<String, Object>>> available = rest.exchange(
                url("/api/vehicles?status=AVAILABLE"), HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        assertThat(available.getBody()).hasSize(4);
    }

    @Test
    void nearestQueryReturnsARoadRankedShortlistWellUnderOneSecond() {
        HttpHeaders auth = login("dispatch", "dispatch");

        long start = System.nanoTime();
        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/vehicles/nearest?lat=42.3601&lon=-71.0589&limit=3"),
                HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody().get(0).get("vehicleId")).isEqualTo("CAR-2"); // on the job point
        // Ranking key present and ascending.
        double firstEta = ((Number) resp.getBody().get(0).get("travelSeconds")).doubleValue();
        double lastEta = ((Number) resp.getBody().get(resp.getBody().size() - 1)
                .get("travelSeconds")).doubleValue();
        assertThat(firstEta).isLessThanOrEqualTo(lastEta);
        assertThat(elapsedMs).isLessThan(1000L); // NFR-1
    }

    @Test
    void createJobThenAssignInOneActionFlipsVehicleToEnRoute() {
        HttpHeaders auth = login("dispatch", "dispatch");

        ResponseEntity<Map<String, Object>> created = rest.exchange(url("/api/jobs"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("destinationAddress", "1 Test Plaza",
                        "destLatitude", 42.3601, "destLongitude", -71.0589), auth),
                new ParameterizedTypeReference<>() { });
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> job = (Map<String, Object>) created.getBody().get("job");
        String jobId = (String) job.get("id");
        assertThat(((List<?>) created.getBody().get("nearestAvailable"))).isNotEmpty();

        ResponseEntity<Map<String, Object>> assigned = rest.exchange(
                url("/api/jobs/" + jobId + "/assign"), HttpMethod.POST,
                new HttpEntity<>(Map.of("vehicleId", "CAR-2"), auth),
                new ParameterizedTypeReference<>() { });
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assigned.getBody().get("assignedVehicleId")).isEqualTo("CAR-2");

        ResponseEntity<Map<String, Object>> detail = rest.exchange(
                url("/api/vehicles/CAR-2"), HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        Map<String, Object> vehicle = (Map<String, Object>) detail.getBody().get("vehicle");
        assertThat(vehicle.get("status")).isEqualTo("EN_ROUTE");
        assertThat(detail.getBody().get("currentJob")).isNotNull();
    }

    @Test
    void assigningAnAlreadyAssignedJobConflicts() {
        HttpHeaders auth = login("dispatch", "dispatch");
        ResponseEntity<Map<String, Object>> created = rest.exchange(url("/api/jobs"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("destinationAddress", "x",
                        "destLatitude", 42.36, "destLongitude", -71.06), auth),
                new ParameterizedTypeReference<>() { });
        String jobId = (String) ((Map<?, ?>) created.getBody().get("job")).get("id");

        rest.exchange(url("/api/jobs/" + jobId + "/assign"), HttpMethod.POST,
                new HttpEntity<>(Map.of("vehicleId", "CAR-1"), auth),
                new ParameterizedTypeReference<Map<String, Object>>() { });
        ResponseEntity<String> second = rest.exchange(url("/api/jobs/" + jobId + "/assign"),
                HttpMethod.POST, new HttpEntity<>(Map.of("vehicleId", "CAR-2"), auth),
                String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void dataSourcePanelDisclosesTheSimulatorAsASubstitute() {
        HttpHeaders auth = login("ops", "ops");
        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(url("/api/data-sources"),
                HttpMethod.GET, new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() { });
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> sim = resp.getBody().stream()
                .filter(d -> "simulator".equals(d.get("key")))
                .findFirst().orElseThrow();
        assertThat(sim.get("role")).isEqualTo("SUBSTITUTE");
        assertThat((String) sim.get("disclosure")).contains("SIMULATED");
    }
}
