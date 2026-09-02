package com.tessera.fleet.ingestion;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.tessera.fleet.durable.PositionRecord;
import com.tessera.fleet.durable.WriteBehindService;
import com.tessera.fleet.geofence.GeofenceService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.PositionReport;

/**
 * Pulls the active {@link PositionSource} on a fixed cadence and, for every
 * report: updates the live layer, evaluates geofences, and enqueues the fix for
 * write-behind persistence. Deliberately resilient — a bad report, a feed
 * outage, or a slow/unreachable database is logged and skipped, never propagated
 * (SRS §2.5 — the live path must keep running).
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final PositionSource source;
    private final LiveFleetService liveFleet;
    private final GeofenceService geofenceService;
    private final WriteBehindService writeBehind;

    private final AtomicLong appliedTotal = new AtomicLong();
    private final AtomicLong rejectedTotal = new AtomicLong();
    private volatile long lastBatchEpochMs = 0L;
    private volatile int lastBatchSize = 0;

    public IngestionService(PositionSource source, LiveFleetService liveFleet,
                            GeofenceService geofenceService, WriteBehindService writeBehind) {
        this.source = source;
        this.liveFleet = liveFleet;
        this.geofenceService = geofenceService;
        this.writeBehind = writeBehind;
    }

    @Scheduled(fixedDelayString = "${tessera.ingest-poll-millis}")
    public void pump() {
        List<PositionReport> batch;
        try {
            batch = source.poll();
        } catch (Exception e) {
            log.warn("Position source {} poll failed: {}", source.id(), e.toString());
            return;
        }
        int applied = 0;
        for (PositionReport report : batch) {
            try {
                liveFleet.applyReport(report);
                geofenceService.onPosition(report.vehicleId(),
                        report.latitude(), report.longitude(), report.epochMillis());
                writeBehind.offerPosition(new PositionRecord(report.vehicleId(),
                        report.latitude(), report.longitude(),
                        report.speedKph(), report.headingDeg(), report.epochMillis()));
                applied++;
            } catch (Exception e) {
                rejectedTotal.incrementAndGet();
                log.debug("Rejected report for {}: {}", report.vehicleId(), e.toString());
            }
        }
        if (!batch.isEmpty()) {
            appliedTotal.addAndGet(applied);
            lastBatchEpochMs = System.currentTimeMillis();
            lastBatchSize = applied;
        }
    }

    public PositionSource source() {
        return source;
    }

    public long appliedTotal() {
        return appliedTotal.get();
    }

    public long rejectedTotal() {
        return rejectedTotal.get();
    }

    public long lastBatchEpochMs() {
        return lastBatchEpochMs;
    }

    public int lastBatchSize() {
        return lastBatchSize;
    }
}
