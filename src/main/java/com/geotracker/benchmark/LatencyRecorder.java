package com.geotracker.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LatencyRecorder {
    private final List<Long> samples = new ArrayList<>();
    private long totalOps = 0;

    public synchronized void record(long latencyNanos) {
        samples.add(latencyNanos);
        totalOps++;
    }

    public synchronized double percentile(double p) {
        if (samples.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index)) / 1_000_000.0;
    }

    public synchronized double mean() {
        if (samples.isEmpty()) return 0;
        double sum = 0;
        for (long s : samples) sum += s;
        return (sum / samples.size()) / 1_000_000.0;
    }

    public synchronized long getTotalOps() {
        return totalOps;
    }

    public synchronized void reset() {
        samples.clear();
        totalOps = 0;
    }

    public synchronized int size() {
        return samples.size();
    }
}
