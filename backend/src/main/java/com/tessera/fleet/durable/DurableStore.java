package com.tessera.fleet.durable;

import java.util.List;
import java.util.Optional;

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

    // ---- jobs: write-through from the live JobService

    void saveJob(JobRecord job);

    List<JobRecord> loadJobs();

    // ---- history reads (Phase 2 needs simple lists; Phase 3 builds aggregates)

    List<GeofenceEventRecord> recentGeofenceEvents(String vehicleId, String siteId, int limit);

    Optional<GeofenceEventRecord> lastGeofenceEvent(String vehicleId, String siteId);

    long positionCount();

    /** Whether durable writes are currently succeeding (drives the health indicator). */
    boolean healthy();
}
