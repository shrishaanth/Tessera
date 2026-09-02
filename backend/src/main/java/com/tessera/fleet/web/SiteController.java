package com.tessera.fleet.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.geofence.Site;
import com.tessera.fleet.geofence.SiteDefinition;
import com.tessera.fleet.geofence.SiteService;

/** Customer-site (geofence) CRUD — FR-3.1. */
@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    public record SiteView(
            String id,
            String name,
            String address,
            String kind,
            List<double[]> outline,
            Double centerLat,
            Double centerLon,
            Double radiusMeters,
            Integer dwellAlertSeconds,
            long createdAtEpochMs) {

        static SiteView of(Site s) {
            return new SiteView(s.id(), s.name(), s.address(),
                    s.radiusMeters() != null ? "RADIUS" : "POLYGON",
                    s.geometry().outlineLatLon(),
                    s.centerLat(), s.centerLon(), s.radiusMeters(),
                    s.dwellAlertSeconds(), s.createdAtEpochMs());
        }
    }

    @GetMapping
    public List<SiteView> list() {
        return siteService.list().stream().map(SiteView::of).toList();
    }

    @GetMapping("/{siteId}")
    public ResponseEntity<SiteView> get(@PathVariable String siteId) {
        return siteService.get(siteId).map(SiteView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<SiteView> create(@RequestBody SiteDefinition body) {
        Site created = siteService.create(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(SiteView.of(created));
    }

    @PutMapping("/{siteId}")
    public SiteView update(@PathVariable String siteId, @RequestBody SiteDefinition body) {
        return SiteView.of(siteService.update(siteId, body));
    }

    @DeleteMapping("/{siteId}")
    public ResponseEntity<Void> delete(@PathVariable String siteId) {
        siteService.delete(siteId);
        return ResponseEntity.noContent().build();
    }
}
