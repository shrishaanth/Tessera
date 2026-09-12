package com.tessera.fleet.durable;

import java.util.List;
import java.util.Optional;

import com.tessera.fleet.reporting.ReportingFacts.DataWindow;
import com.tessera.fleet.reporting.ReportingFacts.SiteVisitFact;

/**
 * The durable layer (SRS §3.1): durable, spatially- and temporally-indexed
 * storage for every position fix and every geofence event, plus site definitions
 * and job history.
 *
 * <p>It is written to <em>only</em> via the write-behind path and is never on the
 * critical path of a live dispatch decision (SRS §2.5). Two implementations ship:
 * {@link InMemoryDurableStore} (default; also used by the fast test suite) and a
 * PostgreSQL + PostGIS + TimescaleDB implementation activated by the
 * {@code durable} profile. The seam keeps the live and durable layers
 * independently testable (NFR-8).
 */
public interface DurableStore {

    /** Append a batch of position fixes. Called by the write-behind consumer. */
    void savePositions(List<PositionRecord> batch);

    /** Append a batch of geofence enter/exit events. */
    void saveGeofenceEvents(List<GeofenceEventRecord> batch);

    // ---- sites (FR-3.1): read on the hot path via the geofence engine's cache

    List<SiteRecord> loadSites();

    void saveSite(SiteRecord site);

    void deleteSite(String siteId);

    /** Fuzzy search of customer site names/addresses, best match first (FR-6.3). */
    List<SiteRecord> searchSites(String query, int limit);

    // ---- history reads (Phase 2 needs simple lists; Phase 3 builds aggregates)

    List<GeofenceEventRecord> recentGeofenceEvents(String vehicleId, String siteId, int limit);

    Optional<GeofenceEventRecord> lastGeofenceEvent(String vehicleId, String siteId);

    long positionCount();

    // ---- reporting reads (FR-4). Row counts are bounded for a 20–200 vehicle
    //      fleet; the reporting layer does the grouping and trend maths.

    /** Geofence EXIT events (one per site visit) in {@code [fromMs, toMs)}. */
    List<SiteVisitFact> siteVisits(long fromMs, long toMs);

    /** A vehicle's recorded positions in {@code [fromMs, toMs)}, oldest first (FR-5.1). */
    List<PositionRecord> trajectory(String vehicleId, long fromMs, long toMs);

    /** Extent and volume of durable history, for the FR-4.4 sufficiency gate. */
    DataWindow reportingWindow();

    /** Whether durable writes are currently succeeding (drives the health indicator). */
    boolean healthy();
}
