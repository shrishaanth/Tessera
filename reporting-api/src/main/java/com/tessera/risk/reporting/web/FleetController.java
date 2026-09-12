package com.tessera.risk.reporting.web;

import java.io.IOException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.risk.reporting.FleetService;
import com.tessera.risk.reporting.hbase.SegmentMetricsRepository;
import com.tessera.risk.reporting.model.FleetSnapshot;
import com.tessera.risk.reporting.model.SegmentRisk;
import com.tessera.risk.reporting.model.VehicleRisk;

/** Fleet and per-entity risk state (FR-5.1). */
@RestController
@RequestMapping("/api")
public class FleetController {

    /** Bounds a caller's ability to ask for an unbounded scan. */
    private static final int MAX_WINDOWS = 500;

    private final FleetService fleet;
    private final SegmentMetricsRepository segments;

    public FleetController(FleetService fleet, SegmentMetricsRepository segments) {
        this.fleet = fleet;
        this.segments = segments;
    }

    /** Every vehicle's latest risk window — the dashboard's map and list view. */
    @GetMapping("/fleet")
    public FleetSnapshot fleet() throws IOException {
        return fleet.snapshot();
    }

    /** One vehicle's latest window. */
    @GetMapping("/vehicles/{vehicleId}")
    public ResponseEntity<VehicleRisk> vehicle(@PathVariable String vehicleId) throws IOException {
        return fleet.latest(vehicleId)
                .map(ResponseEntity::ok)
                // 404, not an empty body: "this vehicle has no windows" and "this
                // vehicle does not exist" are the same answer from HBase's point of
                // view, and both mean the client asked for something absent.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** One vehicle's recent windows, newest first — a risk time series. */
    @GetMapping("/vehicles/{vehicleId}/history")
    public List<VehicleRisk> vehicleHistory(
            @PathVariable String vehicleId,
            @RequestParam(required = false) Integer windows) throws IOException {
        return fleet.history(vehicleId, clamp(windows));
    }

    /** One segment's recent windows, newest first. */
    @GetMapping("/segments/{segmentId}/history")
    public List<SegmentRisk> segmentHistory(
            @PathVariable String segmentId,
            @RequestParam(required = false) Integer windows) throws IOException {
        Integer limit = clamp(windows);
        return segments.history(segmentId, limit == null ? 60 : limit);
    }

    private static Integer clamp(Integer windows) {
        if (windows == null) {
            return null;
        }
        return Math.min(Math.max(windows, 1), MAX_WINDOWS);
    }
}
