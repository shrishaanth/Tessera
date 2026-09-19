package com.geotracker.benchmark;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.GridIndex;
import com.geotracker.index.NaiveIndex;
import com.geotracker.index.SpatialIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class SpatialIndexBenchmark {
    private static final int VEHICLE_COUNT = 1000;
    private static final int OPERATIONS = 10000;

    private NaiveIndex naiveIndex;
    private GridIndex gridIndex;
    private CowQuadtree cowQuadtree;
    private BoundingBox bounds;
    private List<UpdateOperation> operations;

    @Setup
    public void setup() {
        bounds = new BoundingBox(0, 0, 1000, 1000);
        naiveIndex = new NaiveIndex();
        gridIndex = new GridIndex(bounds, 50, 50);
        cowQuadtree = new CowQuadtree(bounds);

        operations = new ArrayList<>();
        for (int i = 0; i < OPERATIONS; i++) {
            long vehicleId = i % VEHICLE_COUNT;
            double oldX = Math.random() * 1000;
            double oldY = Math.random() * 1000;
            double newX = Math.random() * 1000;
            double newY = Math.random() * 1000;
            operations.add(new UpdateOperation(vehicleId, oldX, oldY, newX, newY));
        }

        for (int i = 0; i < VEHICLE_COUNT; i++) {
            double x = Math.random() * 1000;
            double y = Math.random() * 1000;
            naiveIndex.insert(i, x, y);
            gridIndex.insert(i, x, y);
            cowQuadtree.insert(i, x, y);
        }
    }

    @Benchmark
    public List<Long> benchmarkNaiveRangeQuery() {
        double cx = 500 + (Math.random() - 0.5) * 100;
        double cy = 500 + (Math.random() - 0.5) * 100;
        return naiveIndex.rangeQuery(new BoundingBox(cx - 10, cy - 10, cx + 10, cy + 10));
    }

    @Benchmark
    public List<Long> benchmarkGridRangeQuery() {
        double cx = 500 + (Math.random() - 0.5) * 100;
        double cy = 500 + (Math.random() - 0.5) * 100;
        return gridIndex.rangeQuery(new BoundingBox(cx - 10, cy - 10, cx + 10, cy + 10));
    }

    @Benchmark
    public List<Long> benchmarkCowQuadtreeRangeQuery() {
        double cx = 500 + (Math.random() - 0.5) * 100;
        double cy = 500 + (Math.random() - 0.5) * 100;
        return cowQuadtree.rangeQuery(new BoundingBox(cx - 10, cy - 10, cx + 10, cy + 10));
    }

    @Benchmark
    public void benchmarkNaiveUpdate() {
        UpdateOperation op = operations.get((int) (Math.random() * operations.size()));
        naiveIndex.update(op.vehicleId, op.oldX, op.oldY, op.newX, op.newY);
    }

    @Benchmark
    public void benchmarkGridUpdate() {
        UpdateOperation op = operations.get((int) (Math.random() * operations.size()));
        gridIndex.update(op.vehicleId, op.oldX, op.oldY, op.newX, op.newY);
    }

    @Benchmark
    public void benchmarkCowQuadtreeUpdate() {
        UpdateOperation op = operations.get((int) (Math.random() * operations.size()));
        cowQuadtree.update(op.vehicleId, op.oldX, op.oldY, op.newX, op.newY);
    }

    private record UpdateOperation(long vehicleId, double oldX, double oldY, double newX, double newY) {}
}
