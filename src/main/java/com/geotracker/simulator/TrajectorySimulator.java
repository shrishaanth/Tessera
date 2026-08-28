package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class TrajectorySimulator {
    private static final int MAX_TRAIL = 50;

    private final String csvResourcePath;
    private final double timeScale;
    private final int vehicleCount;
    private final Map<Long, List<TrajectoryRecord>> trajectories = new HashMap<>();
    private final Map<Long, Integer> cursors = new HashMap<>();
    private double simTime = 0.0;
    private final double dt;

    private record TrajectoryRecord(long timestampMs, double lat, double lng, double speedKmh) {}

    public TrajectorySimulator(String csvResourcePath, int vehicleCount, double timeScale, double dt) {
        this.csvResourcePath = csvResourcePath;
        this.vehicleCount = vehicleCount;
        this.timeScale = timeScale;
        this.dt = dt;
        loadTrajectories();
    }

    private void loadTrajectories() {
        try (InputStream is = getClass().getResourceAsStream(csvResourcePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // skip header
            String currentVid = null;
            List<TrajectoryRecord> currentList = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                String vid = parts[0];
                if (!vid.equals(currentVid)) {
                    if (currentVid != null) {
                        trajectories.put(Long.parseLong(currentVid), currentList);
                    }
                    currentVid = vid;
                    currentList = new ArrayList<>();
                }
                long ts = Long.parseLong(parts[1]);
                double lat = Double.parseDouble(parts[2]);
                double lng = Double.parseDouble(parts[3]);
                double speed = Double.parseDouble(parts[4]);
                currentList.add(new TrajectoryRecord(ts, lat, lng, speed));
            }
            if (currentVid != null) {
                trajectories.put(Long.parseLong(currentVid), currentList);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load trajectories from " + csvResourcePath, e);
        }

        long maxVid = trajectories.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        for (int i = 0; i < vehicleCount; i++) {
            long vid = i % (maxVid + 1);
            cursors.put((long) i, 0);
        }
    }

    public synchronized List<PositionUpdate> tick() {
        simTime += dt * timeScale;
        List<PositionUpdate> updates = new ArrayList<>();
        long simMs = (long) (simTime * 1000.0);

        for (int i = 0; i < vehicleCount; i++) {
            long vid = i;
            List<TrajectoryRecord> traj = trajectories.get(vid % (trajectories.size()));
            if (traj == null || traj.isEmpty()) continue;

            int idx = findClosestIndex(traj, simMs);
            if (idx < 0) idx = 0;
            if (idx >= traj.size()) idx = traj.size() - 1;

            TrajectoryRecord rec = traj.get(idx);
            updates.add(new PositionUpdate(vid, rec.lng(), rec.lat(), System.currentTimeMillis()));
        }
        return updates;
    }

    private int findClosestIndex(List<TrajectoryRecord> traj, long simMs) {
        int lo = 0, hi = traj.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (traj.get(mid).timestampMs() < simMs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo > 0) {
            long d1 = Math.abs(traj.get(lo).timestampMs() - simMs);
            long d2 = Math.abs(traj.get(lo - 1).timestampMs() - simMs);
            if (d2 < d1) return lo - 1;
        }
        return lo;
    }

    public synchronized void reset() {
        simTime = 0.0;
        cursors.clear();
        long maxVid = trajectories.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        for (int i = 0; i < vehicleCount; i++) {
            cursors.put((long) i, 0);
        }
    }
}
