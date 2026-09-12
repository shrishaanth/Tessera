package com.tessera.risk.archive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.spark.sql.Row;

import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.simulation.FleetSimulator;

/**
 * Produces a slice of the fleet's telemetry one reading at a time.
 *
 * <h2>Why an iterator rather than a list</h2>
 * A week of telemetry for the whole fleet is roughly 14 million rows, and even one
 * simulated day is over 500 MB held as Spark rows. Returning a list from a
 * {@code mapPartitions} would materialise all of it before a single byte reached
 * Parquet.
 *
 * <p>Spark pulls from the iterator and writes as it goes, so memory stays flat in
 * the length of the run: the simulation state for this slice's vehicles, plus one
 * tick of readings. The archive can then be made arbitrarily long without the
 * generator needing more heap, which is the difference between a knob that can be
 * turned up and one that cannot.
 */
final class FleetSliceIterator implements Iterator<Row> {

    private final FleetSimulator simulator;
    private final long startMillis;
    private final long tickMillis;
    private final long totalTicks;

    /** Readings for the tick currently being drained; one per vehicle in the slice. */
    private final Deque<Row> pending = new ArrayDeque<>();
    private long tick;

    FleetSliceIterator(FleetSimulator simulator, long startMillis, long tickMillis,
                       long totalTicks) {
        this.simulator = simulator;
        this.startMillis = startMillis;
        this.tickMillis = tickMillis;
        this.totalTicks = totalTicks;
    }

    @Override
    public boolean hasNext() {
        return !pending.isEmpty() || tick < totalTicks;
    }

    @Override
    public Row next() {
        if (pending.isEmpty()) {
            if (tick >= totalTicks) {
                throw new NoSuchElementException("Simulation exhausted");
            }
            advance();
        }
        return pending.poll();
    }

    private void advance() {
        long timestamp = startMillis + tick * tickMillis;
        for (TelemetryEvent event : simulator.tick(timestamp)) {
            pending.add(TelemetryRows.toRow(event));
        }
        tick++;
    }
}
