package com.tessera.fleet.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.geocoding.GeocodeResult;
import com.tessera.fleet.geocoding.GeocodingService;

/**
 * Address autocomplete / geocoding for new-job entry (FR-6.1, FR-6.2).
 * Backed by Nominatim (SRS §5.2) with server-side rate-limiting and caching.
 */
@RestController
public class GeocodeController {

    private final GeocodingService geocoding;

    public GeocodeController(GeocodingService geocoding) {
        this.geocoding = geocoding;
    }

    public record GeocodeResponse(String query, List<GeocodeResult> results, boolean degraded) { }

    @GetMapping("/api/geocode")
    public GeocodeResponse geocode(@RequestParam("q") String query,
                                   @RequestParam(name = "limit", defaultValue = "6") int limit) {
        List<GeocodeResult> results = geocoding.suggest(query, limit);
        return new GeocodeResponse(query, results,
                results.isEmpty() && geocoding.lastCallFailed());
    }
}
