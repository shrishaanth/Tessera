package com.tessera.fleet.ingestion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.model.PositionReport;

/**
 * Live position feed backed by a real public GTFS-Realtime {@code VehiclePositions}
 * feed. Per SRS §2.6 this is genuinely real, continuously updating position data,
 * but it originates from transit vehicles, not a customer fleet, because private
 * fleet telematics is not publicly available. That substitution is disclosed in
 * the product (FR-7.2) via {@link #isSubstitute()} / {@link #disclosure()}.
 */
public class GtfsRealtimePositionSource implements PositionSource {

    private static final Logger log = LoggerFactory.getLogger(GtfsRealtimePositionSource.class);

    private final FleetProperties.Gtfs config;
    private final HttpClient http;
    private volatile long lastFetchEpochMs = 0L;

    public GtfsRealtimePositionSource(FleetProperties.Gtfs config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        if (config.feedUrl() == null || config.feedUrl().isBlank()) {
            throw new IllegalStateException(
                    "tessera.gtfs.feed-url must be set when position-source=GTFS_REALTIME");
        }
    }

    @Override
    public String id() {
        return "gtfs-realtime";
    }

    @Override
    public String displayName() {
        return "GTFS-Realtime feed" + (agency().isBlank() ? "" : " — " + agency());
    }

    @Override
    public boolean isSubstitute() {
        return true;
    }

    @Override
    public String disclosure() {
        String who = agency().isBlank() ? "a public transit agency" : agency();
        return "Live vehicle positions are sourced from " + who + "'s public "
                + "GTFS-Realtime feed. This is real, continuously updating position "
                + "data, but it comes from TRANSIT VEHICLES, not a customer fleet — "
                + "it stands in for production fleet telematics, which is proprietary "
                + "and not publicly available. Feed: " + config.feedUrl();
    }

    private String agency() {
        return config.agencyLabel() == null ? "" : config.agencyLabel().trim();
    }

    @Override
    public List<PositionReport> poll() {
        long now = System.currentTimeMillis();
        long minInterval = Math.max(1000L, config.pollMillis());
        if (now - lastFetchEpochMs < minInterval) {
            return List.of();
        }
        lastFetchEpochMs = now;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(config.feedUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .GET();
            if (config.apiKey() != null && !config.apiKey().isBlank()
                    && config.apiKeyHeader() != null && !config.apiKeyHeader().isBlank()) {
                req.header(config.apiKeyHeader(), config.apiKey());
            }
            HttpResponse<byte[]> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                log.warn("GTFS-Realtime feed returned HTTP {}", resp.statusCode());
                return List.of();
            }
            return parse(FeedMessage.parseFrom(resp.body()), now);
        } catch (Exception e) {
            // Feed hiccups must never disrupt the live layer (SRS §2.5).
            log.warn("GTFS-Realtime poll failed: {}", e.toString());
            return List.of();
        }
    }

    /** Package-private for tests: convert a parsed feed to position reports. */
    List<PositionReport> parse(FeedMessage feed, long fallbackEpochMs) {
        List<PositionReport> out = new ArrayList<>();
        for (FeedEntity entity : feed.getEntityList()) {
            if (!entity.hasVehicle()) {
                continue;
            }
            VehiclePosition vp = entity.getVehicle();
            if (!vp.hasPosition()) {
                continue;
            }
            String vehicleId = vp.hasVehicle() && !vp.getVehicle().getId().isBlank()
                    ? vp.getVehicle().getId()
                    : entity.getId();
            if (vehicleId == null || vehicleId.isBlank()) {
                continue;
            }
            double lat = vp.getPosition().getLatitude();
            double lon = vp.getPosition().getLongitude();
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180
                    || (lat == 0.0 && lon == 0.0)) {
                continue;
            }
            double heading = vp.getPosition().hasBearing()
                    ? vp.getPosition().getBearing() : Double.NaN;
            double speedKph = vp.getPosition().hasSpeed()
                    ? vp.getPosition().getSpeed() * 3.6 : Double.NaN;
            long ts = vp.hasTimestamp() && vp.getTimestamp() > 0
                    ? vp.getTimestamp() * 1000L : fallbackEpochMs;
            String label = vp.hasVehicle() && !vp.getVehicle().getLabel().isBlank()
                    ? vp.getVehicle().getLabel() : null;
            out.add(new PositionReport("GTFS-" + vehicleId, label, lat, lon,
                    heading, speedKph, ts));
        }
        return out;
    }
}
