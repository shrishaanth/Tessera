package com.tessera.fleet.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisGeoCommands.DistanceUnit;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.model.StatusChange;
import com.tessera.fleet.model.Vehicle;
import com.tessera.fleet.model.VehicleStatus;

/**
 * The live layer (SRS §3.1): the sole authority on where every vehicle is right
 * now and whether it is free. Backed entirely by Redis — an in-memory geospatial
 * index (GEOADD / GEOSEARCH) plus a hash per vehicle — so no disk I/O sits in the
 * path of a live dispatch decision. The durable layer never gates anything here.
 */
@Service
public class LiveFleetService {

    private static final String GEO_KEY = "tessera:fleet:geo";
    private static final String VEHICLES_KEY = "tessera:fleet:vehicles";
    private static final int HISTORY_LIMIT = 25;

    private final StringRedisTemplate redis;
    private final VehicleStatusResolver statusResolver;

    public LiveFleetService(StringRedisTemplate redis, VehicleStatusResolver statusResolver) {
        this.redis = redis;
        this.statusResolver = statusResolver;
    }

    private static String vehicleKey(String id) {
        return "tessera:fleet:vehicle:" + id;
    }

    private static String historyKey(String id) {
        return "tessera:fleet:vehicle:" + id + ":history";
    }

    // ---------------------------------------------------------------- ingestion

    /** Apply one position report to the live layer. Called by the ingestion loop. */
    public void applyReport(PositionReport r) {
        redis.opsForGeo().add(GEO_KEY, new Point(r.longitude(), r.latitude()), r.vehicleId());
        redis.opsForSet().add(VEHICLES_KEY, r.vehicleId());

        String key = vehicleKey(r.vehicleId());
        Map<String, String> fields = new java.util.HashMap<>();
        fields.put("lat", Double.toString(r.latitude()));
        fields.put("lon", Double.toString(r.longitude()));
        fields.put("heading", Double.toString(r.headingDeg()));
        fields.put("speed", Double.toString(r.speedKph()));
        fields.put("lastReport", Long.toString(r.epochMillis()));
        if (r.driverName() != null && !r.driverName().isBlank()) {
            fields.put("driver", r.driverName());
        }
        redis.opsForHash().putAll(key, fields);

        recordTransitionIfChanged(r.vehicleId(), r.epochMillis());
    }

    /** Assign a job to a vehicle (FR-2.4); flips its resolved status to EN_ROUTE. */
    public void setCurrentJob(String vehicleId, String jobId) {
        redis.opsForHash().put(vehicleKey(vehicleId), "jobId", jobId);
        recordTransitionIfChanged(vehicleId, System.currentTimeMillis());
    }

    public void clearCurrentJob(String vehicleId) {
        redis.opsForHash().delete(vehicleKey(vehicleId), "jobId");
        recordTransitionIfChanged(vehicleId, System.currentTimeMillis());
    }

    /** Record which customer site a vehicle is inside (FR-3.2); drives ON_SITE. */
    public void setOnSite(String vehicleId, String siteId) {
        if (siteId == null) {
            clearOnSite(vehicleId);
            return;
        }
        redis.opsForHash().put(vehicleKey(vehicleId), "onSiteId", siteId);
        recordTransitionIfChanged(vehicleId, System.currentTimeMillis());
    }

    public void clearOnSite(String vehicleId) {
        redis.opsForHash().delete(vehicleKey(vehicleId), "onSiteId");
        recordTransitionIfChanged(vehicleId, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- queries

    public List<Vehicle> allVehicles() {
        Set<String> ids = redis.opsForSet().members(VEHICLES_KEY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Vehicle> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            Vehicle v = readVehicle(id, now);
            if (v != null) {
                out.add(v);
            }
        }
        out.sort((a, b) -> a.vehicleId().compareTo(b.vehicleId()));
        return out;
    }

    public List<Vehicle> vehiclesWithStatus(VehicleStatus status) {
        return allVehicles().stream().filter(v -> v.status() == status).toList();
    }

    public Vehicle getVehicle(String id) {
        return readVehicle(id, System.currentTimeMillis());
    }

    public boolean exists(String id) {
        Boolean member = redis.opsForSet().isMember(VEHICLES_KEY, id);
        return Boolean.TRUE.equals(member);
    }

    /** Recent status transitions, newest last (FR-1.4). */
    public List<StatusChange> statusHistory(String id) {
        List<String> raw = redis.opsForList().range(historyKey(id), 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<StatusChange> out = new ArrayList<>(raw.size());
        for (String entry : raw) {
            int sep = entry.indexOf('|');
            if (sep <= 0) {
                continue;
            }
            try {
                long ts = Long.parseLong(entry.substring(0, sep));
                VehicleStatus st = VehicleStatus.valueOf(entry.substring(sep + 1));
                out.add(new StatusChange(st, ts));
            } catch (IllegalArgumentException ignored) {
                // skip malformed history entry
            }
        }
        return out;
    }

    /**
     * Straight-line radius prefilter used before road-network ranking (FR-2).
     * Uses GEORADIUS (SRS §3.2 lists it explicitly) for broad Redis-version
     * compatibility.
     */
    public List<GeoCandidate> searchNearby(double lat, double lon, double radiusMeters, int limit) {
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs
                .newGeoRadiusArgs()
                .includeCoordinates()
                .includeDistance()
                .sortAscending()
                .limit(limit);
        Circle within = new Circle(new Point(lon, lat),
                new Distance(radiusMeters, DistanceUnit.METERS));
        GeoResults<GeoLocation<String>> results = redis.opsForGeo().radius(GEO_KEY, within, args);
        if (results == null) {
            return List.of();
        }
        List<GeoCandidate> out = new ArrayList<>();
        for (GeoResult<GeoLocation<String>> gr : results) {
            GeoLocation<String> loc = gr.getContent();
            Point p = loc.getPoint();
            double meters = gr.getDistance() != null
                    ? gr.getDistance().getValue() : Double.NaN;
            out.add(new GeoCandidate(loc.getName(),
                    p != null ? p.getY() : Double.NaN,
                    p != null ? p.getX() : Double.NaN,
                    meters));
        }
        return out;
    }

    /**
     * Periodic sweep (called by the broadcast tick) so time-driven transitions —
     * chiefly a vehicle going OFFLINE — land in status history even without a
     * triggering event.
     */
    public void sweepStatusTransitions() {
        Set<String> ids = redis.opsForSet().members(VEHICLES_KEY);
        if (ids == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String id : ids) {
            recordTransitionIfChanged(id, now);
        }
    }

    // ---------------------------------------------------------------- internals

    private Vehicle readVehicle(String id, long nowEpochMs) {
        Map<Object, Object> h = redis.opsForHash().entries(vehicleKey(id));
        if (h == null || h.isEmpty()) {
            return null;
        }
        double lat = parseDouble(h.get("lat"));
        double lon = parseDouble(h.get("lon"));
        double heading = parseDouble(h.get("heading"));
        double speed = parseDouble(h.get("speed"));
        long lastReport = parseLong(h.get("lastReport"));
        String driver = h.get("driver") != null ? h.get("driver").toString() : null;
        String jobId = h.get("jobId") != null ? h.get("jobId").toString() : null;
        String onSiteId = h.get("onSiteId") != null ? h.get("onSiteId").toString() : null;
        VehicleStatus status = statusResolver.resolve(
                lastReport, nowEpochMs, jobId != null, onSiteId != null);
        return new Vehicle(id, driver, status, lat, lon, heading, speed, lastReport, jobId, onSiteId);
    }

    private void recordTransitionIfChanged(String id, long nowEpochMs) {
        String key = vehicleKey(id);
        Map<Object, Object> h = redis.opsForHash().entries(key);
        if (h == null || h.isEmpty()) {
            return;
        }
        long lastReport = parseLong(h.get("lastReport"));
        boolean hasJob = h.get("jobId") != null;
        boolean onSite = h.get("onSiteId") != null;
        VehicleStatus resolved = statusResolver.resolve(lastReport, nowEpochMs, hasJob, onSite);
        String prev = h.get("status") != null ? h.get("status").toString() : null;
        if (!resolved.name().equals(prev)) {
            redis.opsForHash().put(key, "status", resolved.name());
            String entry = nowEpochMs + "|" + resolved.name();
            redis.opsForList().rightPush(historyKey(id), entry);
            redis.opsForList().trim(historyKey(id), -HISTORY_LIMIT, -1);
        }
    }

    private static double parseDouble(Object o) {
        try {
            return o == null ? Double.NaN : Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static long parseLong(Object o) {
        try {
            return o == null ? 0L : Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Test/support hook: forget everything. */
    public void flushAll() {
        Set<String> ids = redis.opsForSet().members(VEHICLES_KEY);
        if (ids != null) {
            for (String id : ids) {
                redis.delete(vehicleKey(id));
                redis.delete(historyKey(id));
            }
        }
        redis.delete(GEO_KEY);
        redis.delete(VEHICLES_KEY);
    }

    List<String> knownVehicleIds() {
        Set<String> ids = redis.opsForSet().members(VEHICLES_KEY);
        if (ids == null) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>(ids);
        Collections.sort(list);
        return list;
    }
}
