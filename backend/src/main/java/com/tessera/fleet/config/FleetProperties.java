package com.tessera.fleet.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All Phase 1 tunables, bound from the {@code tessera.*} configuration tree.
 *
 * @param offlineAfterSeconds a vehicle with no position report for longer than
 *        this is resolved as {@code OFFLINE} (drives FR-1.1 colour coding).
 * @param ingestPollMillis    how often the ingestion loop pulls the active source.
 * @param broadcastMillis     how often a fleet snapshot is pushed to dispatcher
 *        clients over WebSocket (must keep FR-1.2 / NFR-2 under 2 s end to end).
 * @param nearest             nearest-available-vehicle search tuning (FR-2).
 * @param positionSource      which live position feed to run (SRS §2.6, FR-7).
 * @param simulator           deterministic simulator settings.
 * @param gtfs                real GTFS-Realtime feed settings.
 * @param roadGraphResource   classpath location of the OSM-derived routing graph.
 * @param users               accounts permitted to sign in (NFR-7).
 */
@ConfigurationProperties(prefix = "tessera")
public record FleetProperties(
        int offlineAfterSeconds,
        long ingestPollMillis,
        long broadcastMillis,
        Nearest nearest,
        PositionSourceType positionSource,
        Simulator simulator,
        Gtfs gtfs,
        String roadGraphResource,
        List<User> users) {

    public enum PositionSourceType { SIMULATOR, GTFS_REALTIME }

    /**
     * @param prefilterRadiusMeters straight-line GEOSEARCH radius used to pick
     *        candidate vehicles before road-network ranking.
     * @param maxRadiusMeters       ceiling the radius may grow to when too few
     *        candidates are found.
     * @param shortlistSize         how many ranked vehicles to return (FR-2.3).
     */
    public record Nearest(int prefilterRadiusMeters, int maxRadiusMeters, int shortlistSize) { }

    /**
     * @param vehicleCount   how many simulated vehicles to spawn (SRS sizes the
     *        system for 20–200 vehicles).
     * @param tickMillis     wall-clock interval between simulated position reports.
     * @param seed           RNG seed — fixed for deterministic, reproducible runs.
     * @param availableRatio fraction of the fleet that starts {@code AVAILABLE}.
     */
    public record Simulator(int vehicleCount, long tickMillis, long seed, double availableRatio) { }

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
     * @param username raw username.
     * @param password bcrypt hash (prefix {@code {bcrypt}}) or {@code {noop}} literal for dev.
     * @param role     {@code DISPATCHER} or {@code OPS_MANAGER}.
     */
    public record User(String username, String password, String role) { }
}
