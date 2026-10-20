package com.geotracker.fx;

import com.geotracker.service.ApplicationService;
import com.geotracker.service.ApplicationService.MetricsSample;
import com.geotracker.service.ApplicationService.VehicleInfo;
import javafx.application.Platform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Polls the backend on a background thread and delivers immutable frames to the JavaFX thread.
 * Keeps the (potentially 10,000-vehicle) snapshot and map payload construction off the UI thread, and
 * never queues more than one undelivered frame.
 */
public final class LiveData {

    /** One UI refresh: fleet snapshot plus, about once per second, a metrics sample. */
    public record Frame(long timeMillis, ApplicationService.SimulationState state, List<VehicleInfo> vehicles,
                        String vehiclesJson, MetricsSample metrics) {}

    public static final int HISTORY_SECONDS = 180;

    private final ApplicationService service;
    private final Consumer<Frame> sink;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile boolean mapPayloadWanted = true;
    private volatile boolean running = false;
    private long lastMetricsMillis = 0;

    /** Metrics history, oldest first; only touched on the FX thread. */
    private final ArrayDeque<MetricsSample> history = new ArrayDeque<>();

    public LiveData(ApplicationService service, Consumer<Frame> sink) {
        this.service = service;
        this.sink = sink;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ui-sampler");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        running = true;
        executor.schedule(this::tick, 0, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    /** Whether the next frames should include the JSON payload for the map. */
    public void setMapPayloadWanted(boolean wanted) {
        this.mapPayloadWanted = wanted;
    }

    /** Requests a frame as soon as possible (e.g. after a simulation control was used). */
    public void refreshNow() {
        if (running && !executor.isShutdown()) executor.schedule(this::sampleOnce, 0, TimeUnit.MILLISECONDS);
    }

    /** UI refresh interval: map/table updates are throttled as the fleet grows. */
    public static long intervalFor(int vehicles) {
        if (vehicles <= 100) return 250;
        if (vehicles <= 1_000) return 500;
        return 1_000;
    }

    public List<MetricsSample> history() {
        return new ArrayList<>(history);
    }

    private void tick() {
        if (!running) return;
        sampleOnce();
        if (running && !executor.isShutdown()) {
            executor.schedule(this::tick, intervalFor(service.getTotalVehicleCount()), TimeUnit.MILLISECONDS);
        }
    }

    private void sampleOnce() {
        if (!inFlight.compareAndSet(false, true)) return;
        try {
            long now = System.currentTimeMillis();
            long t0 = System.nanoTime();
            List<VehicleInfo> vehicles = service.getAllVehicles();
            String json = mapPayloadWanted ? MapPayloads.vehicles(vehicles) : null;
            MetricsSample metrics = null;
            if (now - lastMetricsMillis >= 950) {
                if (lastMetricsMillis != 0 && now - lastMetricsMillis > 3_000) {
                    System.err.println("[ui-sampler] no metrics sample for " + (now - lastMetricsMillis) + " ms");
                }
                metrics = service.sampleMetrics();
                lastMetricsMillis = now;
            }
            long sampleMs = (System.nanoTime() - t0) / 1_000_000;
            if (sampleMs > 500) System.err.println("[ui-sampler] sampling took " + sampleMs + " ms");
            Frame frame = new Frame(now, service.getSimulationState(), vehicles, json, metrics);
            long queued = System.nanoTime();
            Platform.runLater(() -> {
                long start = System.nanoTime();
                try {
                    if (frame.metrics() != null) {
                        history.addLast(frame.metrics());
                        while (history.size() > HISTORY_SECONDS) history.removeFirst();
                    }
                    sink.accept(frame);
                } finally {
                    inFlight.set(false);
                    long waitMs = (start - queued) / 1_000_000, runMs = (System.nanoTime() - start) / 1_000_000;
                    if (waitMs > 1_000 || runMs > 250) {
                        System.err.println("[ui] frame waited " + waitMs + " ms for the FX thread, handling took " + runMs + " ms");
                    }
                }
            });
        } catch (RuntimeException e) {
            inFlight.set(false);
            System.err.println("[ui-sampler] " + e);
        }
    }
}
