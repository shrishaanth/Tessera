package com.geotracker.fx;

import com.geotracker.fx.ui.Fmt;
import com.geotracker.fx.ui.Icons;
import com.geotracker.fx.ui.TimeSeriesChart;
import com.geotracker.fx.ui.Ui;
import com.geotracker.index.CowQuadtree;
import com.geotracker.index.GridIndex;
import com.geotracker.index.NaiveIndex;
import com.geotracker.index.SpatialIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.service.ApplicationService;
import com.geotracker.service.ApplicationService.MetricsSample;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.Random;

/** Live pipeline metrics and an on-demand synthetic spatial-index benchmark. */
public class PerformancePane extends ScrollPane implements Page {

    private static final int WINDOW = 120;

    private final AppContext ctx;
    private final Ui.StatTile generated = new Ui.StatTile("Generated updates", Icons.CHART);
    private final Ui.StatTile indexed = new Ui.StatTile("Indexed updates", Icons.CHART);
    private final Ui.StatTile latency = new Ui.StatTile("Queue → index latency", Icons.TIMER);
    private final Ui.StatTile query = new Ui.StatTile("Full-extent range query", Icons.SEARCH);
    private final Ui.StatTile heap = new Ui.StatTile("Heap memory", Icons.TUNE);

    private final TimeSeriesChart throughputChart = new TimeSeriesChart("Throughput",
            "Position updates per second produced by the simulator vs. applied by the index shards (3 s average)",
            "Updates per second", "updates/s", List.of("Generated", "Indexed"), WINDOW, Fmt::rate);
    private final TimeSeriesChart latencyChart = new TimeSeriesChart("Ingest latency",
            "Time from an update's timestamp until an indexer thread applies it (millisecond clock)",
            "Milliseconds", "ms", List.of("Mean", "Max"), WINDOW, v -> Fmt.decimal(v, 1));
    private final TimeSeriesChart queryChart = new TimeSeriesChart("Spatial query latency",
            "One full-extent range query and one 150 m radius query per second, on the live index",
            "Milliseconds", "ms", List.of("Full extent", "150 m radius"), WINDOW, v -> Fmt.decimal(v, 3));
    private final TimeSeriesChart memoryChart = new TimeSeriesChart("Heap memory",
            "JVM heap in use vs. currently committed by the garbage collector",
            "Megabytes", "MB", List.of("Used", "Committed"), WINDOW, v -> Fmt.decimal(v, 0));

    private final ChoiceBox<Integer> benchVehicles = new ChoiceBox<>(FXCollections.observableArrayList(1_000, 10_000, 50_000));
    private final Button runBench = Ui.button("Run benchmark", Icons.TIMER, "primary");
    private final ProgressIndicator benchProgress = new ProgressIndicator();
    private final ObservableList<BenchRow> benchRows = FXCollections.observableArrayList();
    private final Label benchSummary = Ui.label("", "note");

    public PerformancePane(AppContext ctx) {
        this.ctx = ctx;
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("page-scroll");

        HBox header = Ui.pageHeader("Performance", "Measured live from the running backend, sampled once per second");

        FlowPane kpis = new FlowPane(14, 14, generated, indexed, latency, query, heap);
        kpis.widthProperty().addListener((obs, o, w) -> {
            int perRow = w.doubleValue() >= 960 ? 5 : w.doubleValue() >= 640 ? 3 : 2;
            double tile = Math.floor((w.doubleValue() - 14 * (perRow - 1)) / perRow) - 1;
            for (Node n : kpis.getChildren()) ((Region) n).setPrefWidth(Math.max(150, tile));
        });

        GridPane charts = new GridPane();
        charts.setHgap(14);
        charts.setVgap(14);
        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        charts.getColumnConstraints().addAll(half, half);
        charts.add(throughputChart, 0, 0);
        charts.add(latencyChart, 1, 0);
        charts.add(queryChart, 0, 1);
        charts.add(memoryChart, 1, 1);

        benchVehicles.getSelectionModel().select(Integer.valueOf(10_000));
        benchProgress.setPrefSize(22, 22);
        benchProgress.setVisible(false);
        runBench.setOnAction(e -> runBenchmark());
        HBox benchControls = new HBox(10, Ui.label("Vehicles", "field-label"), benchVehicles, runBench, benchProgress);
        benchControls.setAlignment(Pos.CENTER_LEFT);

        TableView<BenchRow> benchTable = new TableView<>(benchRows);
        benchTable.getColumns().addAll(List.of(
                col("Index", 220, BenchRow::name),
                col("Build", 120, BenchRow::insert),
                col("Per insert", 120, BenchRow::perInsert),
                col("10,000 range queries", 170, BenchRow::queries),
                col("Per query", 120, BenchRow::perQuery),
                col("Matches returned", 150, BenchRow::results)));
        benchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        benchTable.setPrefHeight(150);
        benchTable.setPlaceholder(Ui.label("Run the benchmark to compare the three index implementations.", "muted"));

        VBox bench = Ui.card("Spatial index benchmark",
                "Synthetic workload: uniformly random points in the dataset area and 10,000 random boxes (10% × 10% of the area). Independent of the live simulation.",
                benchControls, benchTable, benchSummary,
                Ui.label("Wall-clock timing with System.nanoTime on a single run (no JMH warm-up), so treat it as indicative. "
                        + "The JMH benchmark in src/benchmark gives rigorous numbers.", "note"));

        VBox page = new VBox(header, kpis, charts, bench);
        page.getStyleClass().add("page");
        setContent(page);
    }

    private static TableColumn<BenchRow, String> col(String name, double width, java.util.function.Function<BenchRow, String> f) {
        TableColumn<BenchRow, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cd -> new SimpleStringProperty(f.apply(cd.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        return c;
    }

    @Override public String title() { return "Performance"; }
    @Override public String icon() { return Icons.CHART; }
    @Override public Node view() { return this; }

    @Override
    public void onShow() {
        redrawCharts();
    }

    @Override
    public void onFrame(LiveData.Frame frame) {
        MetricsSample m = frame.metrics();
        if (m == null) return;
        boolean running = m.state() == ApplicationService.SimulationState.RUNNING;
        generated.set(Fmt.rate(m.generatedRate()) + "/s", "Total " + Fmt.count(m.totalGenerated()) + " since reset");
        indexed.set(Fmt.rate(m.indexedRate()) + "/s", "Total " + Fmt.count(m.totalIndexed()) + " · queue " + Fmt.count(m.queueDepth()));
        latency.set(running ? Fmt.millis(m.queueLatencyMeanMs()) : "–", running ? "Mean · max " + m.queueLatencyMaxMs() + " ms in the last second" : "Measured while running");
        query.set(Fmt.millis(m.rangeQueryMs()), "Returns " + Fmt.count(m.trackedVehicles()) + " vehicles · 150 m radius " + Fmt.millis(m.radiusQueryMs()));
        heap.set(Fmt.megabytes(m.heapUsedBytes()), "Committed " + Fmt.megabytes(m.heapCommittedBytes()) + " · max " + Fmt.megabytes(m.heapMaxBytes()));
        redrawCharts();
    }

    private void redrawCharts() {
        List<MetricsSample> h = ctx.liveData() == null ? List.of() : ctx.liveData().history();
        if (h.isEmpty()) return;
        int n = h.size();
        long[] t = new long[n];
        double[][] thr = new double[n][2], lat = new double[n][2], q = new double[n][2], mem = new double[n][2];
        for (int i = 0; i < n; i++) {
            MetricsSample s = h.get(i);
            t[i] = s.wallTimeMillis();
            thr[i][0] = s.generatedRate();
            thr[i][1] = s.indexedRate();
            lat[i][0] = s.queueLatencyMeanMs();
            lat[i][1] = s.queueLatencyMaxMs();
            q[i][0] = s.rangeQueryMs();
            q[i][1] = s.radiusQueryMs();
            mem[i][0] = s.heapUsedBytes() / (1024.0 * 1024.0);
            mem[i][1] = s.heapCommittedBytes() / (1024.0 * 1024.0);
        }
        long now = h.get(n - 1).wallTimeMillis();
        throughputChart.update(t, thr, now);
        latencyChart.update(t, lat, now);
        queryChart.update(t, q, now);
        memoryChart.update(t, mem, now);
    }

    private void runBenchmark() {
        int vehicleCount = benchVehicles.getValue();
        runBench.setDisable(true);
        benchProgress.setVisible(true);
        benchSummary.setText("Running…");
        BoundingBox bounds = ctx.service.getDatasetBounds();
        Thread t = new Thread(() -> {
            List<BenchRow> results = List.of(
                    bench("Copy-on-write quadtree", new CowQuadtree(bounds), bounds, vehicleCount),
                    bench("Uniform grid (50 × 50)", new GridIndex(bounds, 50, 50), bounds, vehicleCount),
                    bench("Naive list scan", new NaiveIndex(), bounds, vehicleCount));
            boolean agree = results.stream().map(BenchRow::results).distinct().count() == 1;
            Platform.runLater(() -> {
                benchRows.setAll(results);
                benchSummary.setText(agree
                        ? "✓ All three indexes returned the same total number of matches, so they agree on this workload."
                        : "The indexes returned different totals — check the differential tests.");
                benchSummary.setStyle(agree ? "-fx-text-fill: #137333;" : "-fx-text-fill: #c5221f;");
                runBench.setDisable(false);
                benchProgress.setVisible(false);
            });
        }, "index-benchmark");
        t.setDaemon(true);
        t.start();
    }

    private static BenchRow bench(String name, SpatialIndex index, BoundingBox bounds, int vehicles) {
        int queries = 10_000;
        double bw = bounds.maxX() - bounds.minX();
        double bh = bounds.maxY() - bounds.minY();
        Random rng = new Random(42);
        double[] xs = new double[vehicles], ys = new double[vehicles];
        for (int i = 0; i < vehicles; i++) {
            xs[i] = bounds.minX() + rng.nextDouble() * bw;
            ys[i] = bounds.minY() + rng.nextDouble() * bh;
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < vehicles; i++) index.insert(i, xs[i], ys[i]);
        if (index instanceof CowQuadtree q) q.publish();
        long insertNanos = System.nanoTime() - t0;

        Random qr = new Random(7);
        long matches = 0;
        long t1 = System.nanoTime();
        for (int i = 0; i < queries; i++) {
            double cx = bounds.minX() + qr.nextDouble() * bw;
            double cy = bounds.minY() + qr.nextDouble() * bh;
            matches += index.rangeQuery(new BoundingBox(cx - bw * 0.05, cy - bh * 0.05, cx + bw * 0.05, cy + bh * 0.05)).size();
        }
        long queryNanos = System.nanoTime() - t1;
        return new BenchRow(name, Fmt.nanosAsMillis(insertNanos), Fmt.decimal(insertNanos / (double) vehicles / 1000.0, 2) + " µs",
                Fmt.nanosAsMillis(queryNanos), Fmt.decimal(queryNanos / (double) queries / 1000.0, 2) + " µs", Fmt.count(matches));
    }

    record BenchRow(String name, String insert, String perInsert, String queries, String perQuery, String results) {}
}
