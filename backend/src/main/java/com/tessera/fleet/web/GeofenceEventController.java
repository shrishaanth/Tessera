package com.tessera.fleet.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.GeofenceEventRecord;

/**
 * Reads recorded geofence enter/exit events from the durable layer (FR-3.2/3.3).
 * Phase 3 builds aggregate reporting on top of this data.
 */
@RestController
@RequestMapping("/api/geofence-events")
public class GeofenceEventController {

    private final DurableStore durableStore;

    public GeofenceEventController(DurableStore durableStore) {
        this.durableStore = durableStore;
    }

    @GetMapping
    public List<GeofenceEventRecord> list(
            @RequestParam(name = "vehicleId", required = false) String vehicleId,
            @RequestParam(name = "siteId", required = false) String siteId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return durableStore.recentGeofenceEvents(vehicleId, siteId, Math.min(Math.max(limit, 1), 500));
    }
}
