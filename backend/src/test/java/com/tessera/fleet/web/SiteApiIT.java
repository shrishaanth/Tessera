package com.tessera.fleet.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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

import com.tessera.fleet.support.AbstractRedisIntegrationTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tessera.simulator.vehicle-count=0",
        "tessera.ingest-poll-millis=3600000",
        "tessera.broadcast-millis=3600000"
})
class SiteApiIT extends AbstractRedisIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

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

    @Test
    void sitesRequireAuthentication() {
        assertThat(rest.getForEntity(url("/api/sites"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createListUpdateDeletePolygonAndRadiusSites() {
        HttpHeaders auth = login();

        Map<String, Object> polygon = Map.of(
                "name", "Warehouse 7", "address", "7 Dock Rd",
                "polygon", List.of(
                        List.of(42.3550, -71.0650), List.of(42.3550, -71.0620),
                        List.of(42.3570, -71.0620), List.of(42.3570, -71.0650)));
        ResponseEntity<Map<String, Object>> created = rest.exchange(url("/api/sites"),
                HttpMethod.POST, new HttpEntity<>(polygon, auth),
                new ParameterizedTypeReference<>() { });
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("kind")).isEqualTo("POLYGON");
        assertThat((List<?>) created.getBody().get("outline")).isNotEmpty();
        String polyId = (String) created.getBody().get("id");

        Map<String, Object> radius = Map.of(
                "name", "Yard B", "centerLat", 42.3560, "centerLon", -71.0635,
                "radiusMeters", 120.0, "dwellAlertSeconds", 900);
        ResponseEntity<Map<String, Object>> radiusResp = rest.exchange(url("/api/sites"),
                HttpMethod.POST, new HttpEntity<>(radius, auth),
                new ParameterizedTypeReference<>() { });
        assertThat(radiusResp.getBody().get("kind")).isEqualTo("RADIUS");
        assertThat(radiusResp.getBody().get("dwellAlertSeconds")).isEqualTo(900);
        String radiusId = (String) radiusResp.getBody().get("id");

        ResponseEntity<List<Map<String, Object>>> list = rest.exchange(url("/api/sites"),
                HttpMethod.GET, new HttpEntity<>(auth), new ParameterizedTypeReference<>() { });
        assertThat(list.getBody()).extracting(m -> m.get("id"))
                .contains(polyId, radiusId);

        rest.exchange(url("/api/sites/" + polyId), HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", "Warehouse 7 (renamed)",
                        "polygon", polygon.get("polygon")), auth),
                new ParameterizedTypeReference<Map<String, Object>>() { });
        ResponseEntity<Map<String, Object>> got = rest.exchange(url("/api/sites/" + polyId),
                HttpMethod.GET, new HttpEntity<>(auth), new ParameterizedTypeReference<>() { });
        assertThat(got.getBody().get("name")).isEqualTo("Warehouse 7 (renamed)");

        ResponseEntity<Void> del = rest.exchange(url("/api/sites/" + polyId), HttpMethod.DELETE,
                new HttpEntity<>(auth), Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange(url("/api/sites/" + polyId), HttpMethod.GET,
                new HttpEntity<>(auth), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsASiteWithNoShape() {
        HttpHeaders auth = login();
        ResponseEntity<String> resp = rest.exchange(url("/api/sites"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Shapeless"), auth), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
