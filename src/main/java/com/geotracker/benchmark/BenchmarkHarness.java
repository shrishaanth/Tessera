package com.geotracker.benchmark;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.GridIndex;
import com.geotracker.index.HamtIndex;
import com.geotracker.index.NaiveIndex;
import com.geotracker.index.SpatialIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;
import com.geotracker.model.Position;
import com.geotracker.util.Config;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkHarness {
    public record BenchmarkResult(String name, double throughput, double p50, double p95, double p99, double mean) {}

    public static void main(String[] args) throws Exception {
        int vehicleCount = 1000;
        int operations = 10000;
        BoundingBox bounds = new BoundingBox(Config.MAP_MIN_X, Config.MAP_MIN_Y, Config.MAP_MAX_X, Config.MAP_MAX_Y);

        System.out.println("=== Tessera Benchmark Harness ===");
        System.out.println("Vehicle count: " + vehicleCount);
        System.out.println("Operations: " + operations);
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<>();
        results.add(benchmark("NaiveIndex", () -> new NaiveIndex(), vehicleCount, operations));
        results.add(benchmark("GridIndex", () -> new GridIndex(bounds, 50, 50), vehicleCount, operations));
        results.add(benchmark("CowQuadtree", () -> new CowQuadtree(bounds), vehicleCount, operations));

        System.out.println();
        System.out.println("=== Results ===");
        System.out.printf("%-15s %10s %10s %10s %10s %10s%n", "Name", "Throughput", "p50(ms)", "p95(ms)", "p99(ms)", "Mean(ms)");
        for (BenchmarkResult r : results) {
            System.out.printf("%-15s %10.1f %10.2f %10.2f %10.2f %10.2f%n",
                    r.name(), r.throughput(), r.p50(), r.p95(), r.p99(), r.mean());
        }

        String csvPath = "benchmark-results.csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvPath))) {
            pw.println("name,throughput,p50,p95,p99,mean");
            for (BenchmarkResult r : results) {
                pw.printf("%s,%.1f,%.2f,%.2f,%.2f,%.2f%n",
                        r.name(), r.throughput(), r.p50(), r.p95(), r.p99(), r.mean());
            }
        } catch (IOException e) {
            System.err.println("Failed to write CSV: " + e.getMessage());
        }
        System.out.println();
        System.out.println("Results saved to " + csvPath);

        generateChart(results);
    }

    private static void generateChart(List<BenchmarkResult> results) {
        String chartPath = "benchmark-chart.txt";
        try (PrintWriter pw = new PrintWriter(new FileWriter(chartPath))) {
            pw.println("Throughput Comparison (ops/sec)");
            pw.println("=================================");
            double maxThroughput = results.stream().mapToDouble(r -> r.throughput()).max().orElse(1);
            for (BenchmarkResult r : results) {
                int barLength = (int) (40 * r.throughput() / maxThroughput);
                String bar = "=".repeat(Math.max(0, barLength));
                pw.printf("%-15s | %s %10.0f ops/sec%n", r.name(), bar, r.throughput());
            }
        } catch (IOException e) {
            System.err.println("Failed to write chart: " + e.getMessage());
        }
        System.out.println("Chart saved to " + chartPath);
    }

    private static BenchmarkResult benchmark(String name, java.util.function.Supplier<SpatialIndex> factory, int vehicleCount, int operations) {
        SpatialIndex index = factory.get();
        HamtIndex hamt = new HamtIndex();

        for (int i = 0; i < vehicleCount; i++) {
            double x = Math.random() * 1000;
            double y = Math.random() * 1000;
            index.insert(i, x, y);
            hamt.put(i, new Position(x, y, System.currentTimeMillis()));
        }

        LatencyRecorder insertRecorder = new LatencyRecorder();
        LatencyRecorder queryRecorder = new LatencyRecorder();

        long startTime = System.nanoTime();

        for (int i = 0; i < operations; i++) {
            long vehicleId = i % vehicleCount;
            double oldX = Math.random() * 1000;
            double oldY = Math.random() * 1000;
            double newX = Math.random() * 1000;
            double newY = Math.random() * 1000;

            long t0 = System.nanoTime();
            index.update(vehicleId, oldX, oldY, newX, newY);
            insertRecorder.record(System.nanoTime() - t0);

            hamt.put(vehicleId, new Position(newX, newY, System.currentTimeMillis()));

            t0 = System.nanoTime();
            index.rangeQuery(new BoundingBox(newX - 10, newY - 10, newX + 10, newY + 10));
            queryRecorder.record(System.nanoTime() - t0);
        }

        long endTime = System.nanoTime();
        double elapsedSec = (endTime - startTime) / 1_000_000_000.0;
        double throughput = operations / elapsedSec;

        System.out.println(name + ":");
        System.out.printf("  Throughput: %.0f ops/sec%n", throughput);
        System.out.printf("  Update p50: %.2f ms, p95: %.2f ms, p99: %.2f ms%n",
                insertRecorder.percentile(50), insertRecorder.percentile(95), insertRecorder.percentile(99));
        System.out.printf("  Query  p50: %.2f ms, p95: %.2f ms, p99: %.2f ms%n",
                queryRecorder.percentile(50), queryRecorder.percentile(95), queryRecorder.percentile(99));

        return new BenchmarkResult(name, throughput, insertRecorder.percentile(50), insertRecorder.percentile(95), insertRecorder.percentile(99), insertRecorder.mean());
    }
}
