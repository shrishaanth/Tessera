package com.tessera.fleet.geocoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.support.TestFixtures;

class GeocodingServiceTest {

    private static final String NOMINATIM_JSON = """
        [
          {"place_id":1,"lat":"42.3600825","lon":"-71.0588801",
           "display_name":"Boston, Suffolk County, Massachusetts, USA",
           "category":"place","type":"city","importance":0.82},
          {"place_id":2,"lat":"42.35843","lon":"-71.05977",
           "display_name":"Boston City Hall, Congress Street, Boston, USA",
           "class":"building","type":"civic","importance":0.41},
          {"place_id":3,"display_name":"no coords here"}
        ]
        """;

    private static FleetProperties.Geocoding cfg(long minIntervalMs) {
        return new FleetProperties.Geocoding("https://nominatim.example.test/",
                "tessera-test/1.0", minIntervalMs, 100, 3000, 8);
    }

    private GeocodingService service(UrlFetcher fetcher, long minIntervalMs) {
        FleetProperties base = TestFixtures.fleetProperties();
        FleetProperties props = new FleetProperties(base.offlineAfterSeconds(),
                base.ingestPollMillis(), base.broadcastMillis(), base.nearest(),
                base.positionSource(), base.simulator(), base.gtfs(), base.roadGraphResource(),
                base.geofence(), base.durable(), cfg(minIntervalMs), base.replay(), base.users());
        return new GeocodingService(fetcher, new ObjectMapper(), props);
    }

    @Test
    void parsesNominatimJsonAndSkipsEntriesWithoutCoordinates() {
        GeocodingService svc = service((uri, h, t) -> NOMINATIM_JSON, 0);
        List<GeocodeResult> results = svc.parse(NOMINATIM_JSON);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).displayName()).startsWith("Boston, Suffolk");
        assertThat(results.get(0).latitude()).isEqualTo(42.3600825);
        assertThat(results.get(0).longitude()).isEqualTo(-71.0588801);
        assertThat(results.get(0).category()).isEqualTo("place");
        assertThat(results.get(1).category()).isEqualTo("building"); // falls back to "class"
    }

    @Test
    void shortQueriesNeverHitTheUpstream() {
        AtomicInteger calls = new AtomicInteger();
        GeocodingService svc = service((uri, h, t) -> {
            calls.incrementAndGet();
            return NOMINATIM_JSON;
        }, 0);

        assertThat(svc.suggest("bo", 5)).isEmpty();
        assertThat(svc.suggest("  ", 5)).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void cachesRepeatedQueries() {
        AtomicInteger calls = new AtomicInteger();
        GeocodingService svc = service((uri, h, t) -> {
            calls.incrementAndGet();
            return NOMINATIM_JSON;
        }, 0);

        assertThat(svc.suggest("boston city hall", 5)).hasSize(2);
        assertThat(svc.suggest("Boston City Hall", 5)).hasSize(2); // case-insensitive key
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void spacesUpstreamCallsByAtLeastTheConfiguredInterval() {
        GeocodingService svc = service((uri, h, t) -> NOMINATIM_JSON, 250);

        long start = System.currentTimeMillis();
        svc.suggest("boston common", 5);
        svc.suggest("faneuil hall", 5); // different query -> second upstream call
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(240L);
    }

    @Test
    void sendsAUserAgentAndReturnsEmptyOnFailure() {
        AtomicInteger sawUserAgent = new AtomicInteger();
        UrlFetcher failing = (URI uri, Map<String, String> headers, int timeout) -> {
            if (headers.containsKey("User-Agent")) {
                sawUserAgent.incrementAndGet();
            }
            throw new IOException("HTTP 429");
        };
        GeocodingService svc = service(failing, 0);

        assertThat(svc.suggest("boston", 5)).isEmpty();
        assertThat(svc.lastCallFailed()).isTrue();
        assertThat(sawUserAgent.get()).isEqualTo(1);
    }

    @Test
    void geocodeReturnsTheTopMatch() {
        GeocodingService svc = service((uri, h, t) -> NOMINATIM_JSON, 0);
        assertThat(svc.geocode("boston").orElseThrow().type()).isEqualTo("city");
    }
}
