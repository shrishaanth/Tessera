package com.tessera.fleet.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All runtime tunables, bound from the {@code tessera.*} configuration tree.
 *
 * @param offlineAfterSeconds a vehicle with no position report for longer than
 *        this is resolved as {@code OFFLINE} (drives FR-1.1 colour coding).
 * @param ingestPollMillis    how often the ingestion loop pulls the feed.
 * @param broadcastMillis     how often a fleet snapshot is pushed to clients over
 *        WebSocket (must keep FR-1.2 / NFR-2 under 2 s end to end).
 * @param positionSource      which live position feed to run (SRS §2.6, FR-7).
 * @param dataset             pre-generated trajectory dataset settings.
 * @param gtfs                real GTFS-Realtime feed settings (SRS §2.6, FR-7).
 * @param roadGraphResource   classpath location of the OSM-derived road graph
 *        (a real data source shown in the transparency panel, FR-7).
 * @param geofence            geofencing &amp; dwell-time tuning (FR-3).
 * @param durable             durable-layer write-behind settings (SRS §3.1).
 * @param geocoding           address / site geocoding via Nominatim (FR-6).
 * @param replay              trajectory-replay tuning (FR-5).
 * @param users               accounts permitted to sign in (NFR-7).
 */
@ConfigurationProperties(prefix = "tessera")
public record FleetProperties(
        int offlineAfterSeconds,
        long ingestPollMillis,
        long broadcastMillis,
        PositionSourceType positionSource,
        Dataset dataset,
        Gtfs gtfs,
        String roadGraphResource,
        Geofence geofence,
        Durable durable,
        Geocoding geocoding,
        Replay replay,
        List<User> users) {

    /**
     * Which live position feed to run.
     *
     * <ul>
     *   <li>{@code DATASET} — replay a pre-generated, physically plausible
     *       trajectory dataset (default; also what the tests use).</li>
     *   <li>{@code GTFS_REALTIME} — a real public transit agency feed.</li>
     * </ul>
     */
    public enum PositionSourceType { DATASET, GTFS_REALTIME }

    /**
     * @param resource classpath/file location of the gzipped NDJSON trajectory
     *        dataset produced by {@code FleetDatasetGenerator}
     */
    public record Dataset(String resource) { }

    /**
     * @param feedUrl       URL of a real public GTFS-Realtime {@code VehiclePositions} feed.
     * @param apiKey        optional key, sent as a header if {@code apiKeyHeader} is set.
     * @param apiKeyHeader  header name to carry {@code apiKey}.
     * @param pollMillis    how often to pull the feed.
     * @param agencyLabel   human name of the agency, shown verbatim in the data-source
     *        disclosure (FR-7.2).
     */
    public record Gtfs(String feedUrl, String apiKey, String apiKeyHeader,
                       long pollMillis, String agencyLabel) { }

    /**
     * @param debounceSeconds          minimum time a vehicle must remain on the new
     *        side of a site boundary before an enter/exit is treated as real,
     *        suppressing GPS jitter (FR-3.4).
     * @param defaultDwellAlertSeconds default threshold above which an on-site
     *        dwell raises a dispatcher alert; a site may override it (FR-3.5).
     */
    public record Geofence(int debounceSeconds, int defaultDwellAlertSeconds) { }

    /**
     * @param mode          {@code in-memory} or {@code postgres}.
     * @param queueCapacity bounded write-behind queue size; overflow is dropped
     *        and counted, never blocked (SRS §2.5).
     * @param batchSize     max rows per durable insert batch.
     * @param flushMillis   max time a partial batch waits before being written.
     * @param datasource    PostgreSQL connection settings (used when mode=postgres).
     */
    public record Durable(String mode, int queueCapacity, int batchSize, long flushMillis,
                          DataSource datasource) {

        public boolean postgres() {
            return "postgres".equalsIgnoreCase(mode);
        }
    }

    public record DataSource(String url, String username, String password) { }

    /**
     * Address / site geocoding via Nominatim (SRS §5.2 — free, public,
     * rate-limited). Point {@code baseUrl} at a self-hosted instance to lift the
     * rate limit.
     *
     * @param baseUrl        Nominatim base URL
     * @param userAgent      required by Nominatim's usage policy; identifies this app
     * @param minIntervalMs  minimum spacing between upstream calls (public policy: ≥ 1 s)
     * @param cacheSize      how many recent queries to cache
     * @param timeoutMs      upstream request timeout
     * @param maxResults     cap on suggestions returned
     */
    public record Geocoding(String baseUrl, String userAgent, long minIntervalMs,
                            int cacheSize, int timeoutMs, int maxResults) { }

    /**
     * @param maxPoints  a replayed path is stride-sampled down to at most this
     *        many points so the browser can draw it (FR-5.1)
     */
    public record Replay(int maxPoints) { }

    /**
     * @param username raw username.
     * @param password bcrypt hash (prefix {@code {bcrypt}}) or {@code {noop}} literal for dev.
     * @param role     {@code DISPATCHER} or {@code OPS_MANAGER}.
     */
    public record User(String username, String password, String role) { }
}
