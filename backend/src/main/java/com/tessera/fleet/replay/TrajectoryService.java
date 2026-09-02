package com.tessera.fleet.replay;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.PositionRecord;

/**
 * Trajectory replay (FR-5.1): a vehicle's full recorded path for a day, read from
 * the durable {@code positions} history (SRS §3.1) and stride-sampled down to a
 * browser-friendly point count. Request/response, not real-time (SRS §5.3).
 */
@Service
public class TrajectoryService {

    private final DurableStore durableStore;
    private final int maxPoints;

    public TrajectoryService(DurableStore durableStore, FleetProperties properties) {
        this.durableStore = durableStore;
        this.maxPoints = Math.max(100, properties.replay().maxPoints());
    }

    public record TrajectoryPoint(double latitude, double longitude, long epochMillis,
                                  double speedKph, double headingDeg) { }

    /**
     * @param totalPoints raw point count before sampling
     * @param sampled     whether {@code points} was stride-sampled
     */
    public record Trajectory(String vehicleId, long fromEpochMs, long toEpochMs,
                             int totalPoints, boolean sampled, List<TrajectoryPoint> points) { }

    public Trajectory forDay(String vehicleId, LocalDate dayUtc) {
        long from = dayUtc.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long to = from + 86_400_000L;
        return between(vehicleId, from, to);
    }

    public Trajectory between(String vehicleId, long fromMs, long toMs) {
        List<PositionRecord> raw = durableStore.trajectory(vehicleId, fromMs, toMs);
        int total = raw.size();
        List<PositionRecord> use = raw;
        boolean sampled = false;
        if (total > maxPoints) {
            int stride = (int) Math.ceil((double) total / maxPoints);
            List<PositionRecord> down = new ArrayList<>(maxPoints + 1);
            for (int i = 0; i < total; i += stride) {
                down.add(raw.get(i));
            }
            PositionRecord last = raw.get(total - 1);
            if (down.isEmpty() || down.get(down.size() - 1) != last) {
                down.add(last);
            }
            use = down;
            sampled = true;
        }
        List<TrajectoryPoint> points = new ArrayList<>(use.size());
        for (PositionRecord p : use) {
            points.add(new TrajectoryPoint(p.latitude(), p.longitude(), p.epochMillis(),
                    p.speedKph(), p.headingDeg()));
        }
        return new Trajectory(vehicleId, fromMs, toMs, total, sampled, points);
    }
}
