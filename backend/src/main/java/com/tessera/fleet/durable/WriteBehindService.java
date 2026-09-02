package com.tessera.fleet.durable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import com.tessera.fleet.config.FleetProperties;

/**
 * The write-behind path to the durable layer (SRS §3.1, §2.5).
 *
 * <p>Producers ({@code IngestionService}, {@code GeofenceService}) call the
 * non-blocking {@code offer*} methods. A single daemon thread drains the bounded
 * queues in batches and hands them to the {@link DurableStore}. If the queue is
 * full (store slow) the item is dropped and counted; if a write fails the batch
 * is retried a few times then dropped. Either way the live path is never blocked
 * and live dispatch keeps working (NFR-3).
 */
@Service
public class WriteBehindService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WriteBehindService.class);
    private static final int MAX_RETRIES = 3;

    private final DurableStore store;
    private final int batchSize;
    private final long flushMillis;

    private final BlockingQueue<PositionRecord> positionQueue;
    private final BlockingQueue<GeofenceEventRecord> eventQueue;

    private final AtomicLong enqueuedPositions = new AtomicLong();
    private final AtomicLong writtenPositions = new AtomicLong();
    private final AtomicLong droppedFull = new AtomicLong();
    private final AtomicLong droppedError = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private volatile String lastError;
    private volatile boolean writesHealthy = true;

    private volatile boolean running;
    private Thread worker;

    public WriteBehindService(DurableStore store, FleetProperties properties) {
        this.store = store;
        FleetProperties.Durable cfg = properties.durable();
        this.batchSize = Math.max(1, cfg.batchSize());
        this.flushMillis = Math.max(50, cfg.flushMillis());
        int capacity = Math.max(1000, cfg.queueCapacity());
        this.positionQueue = new ArrayBlockingQueue<>(capacity);
        this.eventQueue = new ArrayBlockingQueue<>(Math.max(256, capacity / 20));
    }

    // ----------------------------------------------------- producer API (hot path)

    public void offerPosition(PositionRecord record) {
        if (positionQueue.offer(record)) {
            enqueuedPositions.incrementAndGet();
        } else {
            droppedFull.incrementAndGet();
        }
    }

    public void offerGeofenceEvent(GeofenceEventRecord event) {
        if (!eventQueue.offer(event)) {
            droppedError.incrementAndGet();
            log.warn("Geofence-event queue full; dropped {} for {}/{}",
                    event.type(), event.vehicleId(), event.siteId());
        }
    }

    // ----------------------------------------------------- consumer

    private void runLoop() {
        while (running) {
            try {
                drainEvents();
                drainPositions();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Write-behind loop error: {}", e.toString());
                sleepQuietly(flushMillis);
            }
        }
    }

    private void drainPositions() throws InterruptedException {
        PositionRecord first = positionQueue.poll(flushMillis, TimeUnit.MILLISECONDS);
        if (first == null) {
            return;
        }
        List<PositionRecord> batch = new ArrayList<>(batchSize);
        batch.add(first);
        positionQueue.drainTo(batch, batchSize - 1);
        writeWithRetry(() -> store.savePositions(batch), batch.size(), "positions");
    }

    private void drainEvents() {
        if (eventQueue.isEmpty()) {
            return;
        }
        List<GeofenceEventRecord> batch = new ArrayList<>();
        eventQueue.drainTo(batch);
        if (!batch.isEmpty()) {
            writeWithRetry(() -> store.saveGeofenceEvents(batch), batch.size(), "geofence-events");
        }
    }

    private void writeWithRetry(Runnable write, int count, String what) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                write.run();
                if (!writesHealthy) {
                    log.info("Durable writes recovered");
                }
                writesHealthy = true;
                if ("positions".equals(what)) {
                    writtenPositions.addAndGet(count);
                }
                return;
            } catch (Exception e) {
                writeFailures.incrementAndGet();
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                writesHealthy = false;
                if (attempt == MAX_RETRIES) {
                    droppedError.addAndGet(count);
                    log.warn("Dropping {} {} after {} failed attempts: {}",
                            count, what, MAX_RETRIES, lastError);
                } else {
                    sleepQuietly(flushMillis * attempt);
                }
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ----------------------------------------------------- lifecycle

    @Override
    public void start() {
        running = true;
        worker = new Thread(this::runLoop, "durable-write-behind");
        worker.setDaemon(true);
        worker.start();
        log.info("Write-behind started (store={}, batchSize={}, flushMillis={})",
                store.getClass().getSimpleName(), batchSize, flushMillis);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Best-effort final flush so a clean shutdown does not lose the tail.
        try {
            List<PositionRecord> rest = new ArrayList<>();
            positionQueue.drainTo(rest);
            if (!rest.isEmpty()) {
                store.savePositions(rest);
            }
            List<GeofenceEventRecord> restEvents = new ArrayList<>();
            eventQueue.drainTo(restEvents);
            if (!restEvents.isEmpty()) {
                store.saveGeofenceEvents(restEvents);
            }
        } catch (Exception e) {
            log.warn("Final write-behind flush failed: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Start after the web server / ingestion; stop before them.
        return Integer.MAX_VALUE - 100;
    }

    // ----------------------------------------------------- observability

    /** For tests: block until the queues are empty or the deadline passes. */
    public boolean awaitDrained(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (positionQueue.isEmpty() && eventQueue.isEmpty()) {
                return true;
            }
            sleepQuietly(25);
        }
        return positionQueue.isEmpty() && eventQueue.isEmpty();
    }

    public Stats stats() {
        return new Stats(enqueuedPositions.get(), writtenPositions.get(), droppedFull.get(),
                droppedError.get(), writeFailures.get(), positionQueue.size(),
                writesHealthy && store.healthy(), lastError);
    }

    public record Stats(long enqueuedPositions, long writtenPositions, long droppedFull,
                        long droppedError, long writeFailures, int queueDepth,
                        boolean healthy, String lastError) { }
}
