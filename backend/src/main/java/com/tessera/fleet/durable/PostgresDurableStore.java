package com.tessera.fleet.durable;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.tessera.fleet.reporting.ReportingFacts.CompletedJobFact;
import com.tessera.fleet.reporting.ReportingFacts.DataWindow;
import com.tessera.fleet.reporting.ReportingFacts.SiteVisitFact;

/**
 * PostgreSQL + PostGIS + TimescaleDB {@link DurableStore}. Plain {@link JdbcTemplate}
 * and explicit SQL — geometry crosses the boundary as WKT
 * ({@code ST_GeomFromText} / {@code ST_AsText}) so nothing here depends on a
 * spatial JDBC driver extension.
 */
public class PostgresDurableStore implements DurableStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresDurableStore.class);

    private final JdbcTemplate jdbc;

    public PostgresDurableStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void savePositions(List<PositionRecord> batch) {
        jdbc.batchUpdate(
                "INSERT INTO positions (vehicle_id, ts, lat, lon, geom, speed_kph, heading_deg) "
                        + "VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?)",
                batch, batch.size(), (ps, r) -> {
                    ps.setString(1, r.vehicleId());
                    ps.setTimestamp(2, new Timestamp(r.epochMillis()));
                    ps.setDouble(3, r.latitude());
                    ps.setDouble(4, r.longitude());
                    ps.setDouble(5, r.longitude());
                    ps.setDouble(6, r.latitude());
                    setNullableDouble(ps, 7, r.speedKph());
                    setNullableDouble(ps, 8, r.headingDeg());
                });
    }

    @Override
    public void saveGeofenceEvents(List<GeofenceEventRecord> batch) {
        jdbc.batchUpdate(
                "INSERT INTO geofence_events (vehicle_id, site_id, event_type, ts, dwell_seconds) "
                        + "VALUES (?, ?, ?, ?, ?)",
                batch, batch.size(), (ps, e) -> {
                    ps.setString(1, e.vehicleId());
                    ps.setString(2, e.siteId());
                    ps.setString(3, e.type().name());
                    ps.setTimestamp(4, new Timestamp(e.epochMillis()));
                    if (e.dwellSeconds() == null) {
                        ps.setNull(5, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(5, e.dwellSeconds());
                    }
                });
    }

    private static final RowMapper<SiteRecord> SITE_MAPPER = (rs, i) -> new SiteRecord(
            rs.getString("site_id"),
            rs.getString("name"),
            rs.getString("address"),
            rs.getString("boundary_wkt"),
            (Double) rs.getObject("center_lat"),
            (Double) rs.getObject("center_lon"),
            (Double) rs.getObject("radius_m"),
            (Integer) rs.getObject("dwell_alert_seconds"),
            rs.getTimestamp("created_at").getTime());

    @Override
    public List<SiteRecord> loadSites() {
        return jdbc.query(
                "SELECT site_id, name, address, ST_AsText(boundary) AS boundary_wkt, "
                        + "center_lat, center_lon, radius_m, dwell_alert_seconds, created_at "
                        + "FROM sites ORDER BY created_at",
                SITE_MAPPER);
    }

    @Override
    public void saveSite(SiteRecord s) {
        jdbc.update(
                "INSERT INTO sites (site_id, name, address, boundary, center_lat, center_lon, "
                        + "radius_m, dwell_alert_seconds, created_at) "
                        + "VALUES (?, ?, ?, ST_GeomFromText(?, 4326), ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (site_id) DO UPDATE SET name = EXCLUDED.name, "
                        + "address = EXCLUDED.address, boundary = EXCLUDED.boundary, "
                        + "center_lat = EXCLUDED.center_lat, center_lon = EXCLUDED.center_lon, "
                        + "radius_m = EXCLUDED.radius_m, "
                        + "dwell_alert_seconds = EXCLUDED.dwell_alert_seconds",
                s.siteId(), s.name(), s.address(), s.boundaryWkt(),
                s.centerLat(), s.centerLon(), s.radiusMeters(), s.dwellAlertSeconds(),
                new Timestamp(s.createdAtEpochMs()));
    }

    @Override
    public void deleteSite(String siteId) {
        jdbc.update("DELETE FROM sites WHERE site_id = ?", siteId);
    }

    @Override
    public void saveJob(JobRecord j) {
        jdbc.update(
                "INSERT INTO jobs (job_id, route, destination_address, dest_lat, dest_lon, "
                        + "site_id, assigned_vehicle_id, driver_name, status, created_at, "
                        + "assigned_at, expected_arrival_at, actual_arrival_at, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (job_id) DO UPDATE SET "
                        + "route = EXCLUDED.route, site_id = EXCLUDED.site_id, "
                        + "assigned_vehicle_id = EXCLUDED.assigned_vehicle_id, "
                        + "driver_name = EXCLUDED.driver_name, status = EXCLUDED.status, "
                        + "assigned_at = EXCLUDED.assigned_at, "
                        + "expected_arrival_at = EXCLUDED.expected_arrival_at, "
                        + "actual_arrival_at = EXCLUDED.actual_arrival_at, "
                        + "completed_at = EXCLUDED.completed_at",
                j.jobId(), j.route(), j.destinationAddress(), j.destLatitude(), j.destLongitude(),
                j.siteId(), j.assignedVehicleId(), j.driverName(), j.status(),
                new Timestamp(j.createdAtEpochMs()),
                ts(j.assignedAtEpochMs()), ts(j.expectedArrivalEpochMs()),
                ts(j.actualArrivalEpochMs()), ts(j.completedAtEpochMs()));
    }

    private static Timestamp ts(Long epochMs) {
        return epochMs == null ? null : new Timestamp(epochMs);
    }

    private static Long millis(Timestamp t) {
        return t == null ? null : t.getTime();
    }

    private static final RowMapper<JobRecord> JOB_MAPPER = (rs, i) -> new JobRecord(
            rs.getString("job_id"),
            rs.getString("route"),
            rs.getString("destination_address"),
            rs.getDouble("dest_lat"),
            rs.getDouble("dest_lon"),
            rs.getString("site_id"),
            rs.getString("assigned_vehicle_id"),
            rs.getString("driver_name"),
            rs.getString("status"),
            rs.getTimestamp("created_at").getTime(),
            millis(rs.getTimestamp("assigned_at")),
            millis(rs.getTimestamp("expected_arrival_at")),
            millis(rs.getTimestamp("actual_arrival_at")),
            millis(rs.getTimestamp("completed_at")));

    @Override
    public List<JobRecord> loadJobs() {
        return jdbc.query("SELECT * FROM jobs ORDER BY created_at DESC", JOB_MAPPER);
    }

    @Override
    public List<CompletedJobFact> completedJobs(long fromMs, long toMs) {
        return jdbc.query(
                "SELECT job_id, route, driver_name, site_id, expected_arrival_at, "
                        + "actual_arrival_at, completed_at FROM jobs "
                        + "WHERE status = 'COMPLETED' AND completed_at >= ? AND completed_at < ?",
                (rs, i) -> new CompletedJobFact(
                        rs.getString("job_id"), rs.getString("route"), rs.getString("driver_name"),
                        rs.getString("site_id"),
                        rs.getTimestamp("expected_arrival_at") == null ? 0
                                : rs.getTimestamp("expected_arrival_at").getTime(),
                        rs.getTimestamp("actual_arrival_at") == null ? 0
                                : rs.getTimestamp("actual_arrival_at").getTime(),
                        rs.getTimestamp("completed_at").getTime()),
                new Timestamp(fromMs), new Timestamp(toMs));
    }

    @Override
    public List<SiteVisitFact> siteVisits(long fromMs, long toMs) {
        return jdbc.query(
                "SELECT site_id, dwell_seconds, ts FROM geofence_events "
                        + "WHERE event_type = 'EXIT' AND dwell_seconds IS NOT NULL "
                        + "AND ts >= ? AND ts < ?",
                (rs, i) -> new SiteVisitFact(rs.getString("site_id"),
                        rs.getInt("dwell_seconds"), rs.getTimestamp("ts").getTime()),
                new Timestamp(fromMs), new Timestamp(toMs));
    }

    @Override
    public DataWindow reportingWindow() {
        Timestamp earliestPos = jdbc.queryForObject("SELECT min(ts) FROM positions", Timestamp.class);
        Timestamp earliestEvt = jdbc.queryForObject("SELECT min(ts) FROM geofence_events", Timestamp.class);
        Timestamp latestPos = jdbc.queryForObject("SELECT max(ts) FROM positions", Timestamp.class);
        Timestamp latestEvt = jdbc.queryForObject("SELECT max(ts) FROM geofence_events", Timestamp.class);
        long earliest = min(millis(earliestPos), millis(earliestEvt));
        long latest = Math.max(millis(latestPos) == null ? 0 : millis(latestPos),
                millis(latestEvt) == null ? 0 : millis(latestEvt));
        Long completed = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE status = 'COMPLETED'", Long.class);
        Long exits = jdbc.queryForObject(
                "SELECT count(*) FROM geofence_events WHERE event_type = 'EXIT'", Long.class);
        return new DataWindow(earliest, latest,
                completed == null ? 0 : completed, exits == null ? 0 : exits);
    }

    private static long min(Long a, Long b) {
        if (a == null) {
            return b == null ? 0 : b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    private static final RowMapper<GeofenceEventRecord> EVENT_MAPPER = (rs, i) -> new GeofenceEventRecord(
            rs.getString("vehicle_id"),
            rs.getString("site_id"),
            GeofenceEventRecord.Type.valueOf(rs.getString("event_type")),
            rs.getTimestamp("ts").getTime(),
            (Integer) rs.getObject("dwell_seconds"));

    @Override
    public List<GeofenceEventRecord> recentGeofenceEvents(String vehicleId, String siteId, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT vehicle_id, site_id, event_type, ts, dwell_seconds FROM geofence_events WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (vehicleId != null) {
            sql.append(" AND vehicle_id = ?");
            args.add(vehicleId);
        }
        if (siteId != null) {
            sql.append(" AND site_id = ?");
            args.add(siteId);
        }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), EVENT_MAPPER, args.toArray());
    }

    @Override
    public Optional<GeofenceEventRecord> lastGeofenceEvent(String vehicleId, String siteId) {
        return recentGeofenceEvents(vehicleId, siteId, 1).stream().findFirst();
    }

    @Override
    public long positionCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM positions", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public boolean healthy() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.debug("Durable health check failed: {}", e.toString());
            return false;
        }
    }

    private static void setNullableDouble(java.sql.PreparedStatement ps, int idx, double value)
            throws java.sql.SQLException {
        if (Double.isNaN(value)) {
            ps.setNull(idx, java.sql.Types.DOUBLE);
        } else {
            ps.setDouble(idx, value);
        }
    }
}
