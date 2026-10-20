package com.geotracker.fx;

import com.geotracker.fx.ui.Fmt;
import com.geotracker.fx.ui.Icons;
import com.geotracker.fx.ui.TimeSeriesChart;
import com.geotracker.fx.ui.Ui;
import com.geotracker.model.ZoneEvent;
import com.geotracker.service.ApplicationService;
import com.geotracker.service.ApplicationService.MetricsSample;
import com.geotracker.service.ApplicationService.VehicleInfo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;

import java.util.List;

/** Fleet overview: KPIs, throughput, status breakdown, recent geofence events and system health. */
public class DashboardPane extends ScrollPane implements Page {

    private final AppContext ctx;
    private final Ui.StateChip chip = new Ui.StateChip();
    private final Label updated = Ui.label("", "muted");

    private final Ui.StatTile simTile = new Ui.StatTile("Simulation", Icons.TIMER);
    private final Ui.StatTile vehiclesTile = new Ui.StatTile("Vehicles tracked", Icons.CAR);
    private final Ui.StatTile rateTile = new Ui.StatTile("Update throughput", Icons.CHART);
    private final Ui.StatTile eventsTile = new Ui.StatTile("Geofence events", Icons.GEOFENCE);
    private final Ui.StatTile speedTile = new Ui.StatTile("Average speed", Icons.NAVIGATION);
    private final Ui.StatTile latencyTile = new Ui.StatTile("Ingest latency", Icons.TIMER);

    private final TimeSeriesChart throughput = new TimeSeriesChart("Update throughput",
            "Position updates generated per second (3 s average), last 2 minutes", "Updates per second", "updates/s",
            List.of("Generated"), 120, Fmt::rate);

    private final StatusBar activeBar = new StatusBar("Active", "active");
    private final StatusBar staleBar = new StatusBar("Stale", "stale");
    private final StatusBar pausedBar = new StatusBar("Paused", "paused");
    private final StatusBar idleBar = new StatusBar("Idle (stopped)", "idle");
    private final Label statusNote = Ui.label("", "muted");

    private final VBox eventsList = new VBox(2);
    private final Label eventsEmpty = Ui.label("No geofence events yet. Start the simulation to see vehicles entering and leaving zones.", "note");

    private final Label shardLine = Ui.label("–", "kv-value");
    private final Label snapshotLine = Ui.label("–", "kv-value");
    private final Label heapLine = Ui.label("–", "kv-value");
    private final ProgressBar heapBar = new ProgressBar(0);
    private final Label tilesLine = Ui.label("–", "kv-value");
    private final Label failedLine = Ui.label("–", "kv-value");
    private long lastEventsRefresh = 0;

    public DashboardPane(AppContext ctx) {
        this.ctx = ctx;
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("page-scroll");

        Button openMap = Ui.button("Open live map", Icons.MAP, "primary");
        openMap.setOnAction(e -> ctx.navigate("Live Map"));
        HBox header = Ui.pageHeader("Dashboard", "Live overview of the simulated fleet and the tracking backend",
                new VBox(4, chip, updated), openMap);

        FlowPane kpis = new FlowPane(14, 14, simTile, vehiclesTile, rateTile, eventsTile, speedTile, latencyTile);
        kpis.setPrefWrapLength(900);
        for (Node n : kpis.getChildren()) ((Region) n).setPrefWidth(208);
        // Tiles stretch to share the row width.
        kpis.widthProperty().addListener((obs, o, w) -> sizeTiles(kpis, w.doubleValue()));

        VBox statusCard = Ui.card("Fleet status", "Vehicles by tracking state",
                activeBar, staleBar, pausedBar, idleBar, statusNote);
        statusCard.setPrefWidth(340);
        statusCard.setMinWidth(300);

        HBox row2 = new HBox(14, throughput, statusCard);
        HBox.setHgrow(throughput, Priority.ALWAYS);

        VBox eventsCard = Ui.card("Recent geofence events", "Newest first", eventsEmpty, eventsList);
        Button allEvents = Ui.button("View all", Icons.GEOFENCE, "ghost");
        allEvents.setOnAction(e -> ctx.navigate("Geofencing"));
        eventsCard.getChildren().add(allEvents);

        GridPane health = Ui.keyValueGrid();
        health.addRow(0, Ui.label("Index shards", "kv-key"), shardLine);
        health.addRow(1, Ui.label("Snapshot age", "kv-key"), snapshotLine);
        health.addRow(2, Ui.label("Heap memory", "kv-key"), new VBox(4, heapLine, heapBar));
        health.addRow(3, Ui.label("Map tiles", "kv-key"), tilesLine);
        health.addRow(4, Ui.label("Index errors", "kv-key"), failedLine);
        heapBar.setMaxWidth(Double.MAX_VALUE);
        heapBar.setPrefHeight(8);
        VBox healthCard = Ui.card("System health", "Backend pipeline and resources", health);
        healthCard.setPrefWidth(380);
        healthCard.setMinWidth(320);

        HBox row3 = new HBox(14, eventsCard, healthCard);
        HBox.setHgrow(eventsCard, Priority.ALWAYS);

        VBox page = new VBox(header, kpis, row2, row3);
        page.getStyleClass().add("page");
        setContent(page);
    }

    private static void sizeTiles(FlowPane pane, double width) {
        int n = pane.getChildren().size();
        double gap = pane.getHgap();
        int perRow = width >= 1180 ? 6 : width >= 760 ? 3 : 2;
        perRow = Math.min(perRow, n);
        double w = Math.floor((width - gap * (perRow - 1)) / perRow) - 1;
        for (Node c : pane.getChildren()) ((Region) c).setPrefWidth(Math.max(150, w));
    }

    @Override public String title() { return "Dashboard"; }
    @Override public String icon() { return Icons.DASHBOARD; }
    @Override public Node view() { return this; }

    @Override
    public void onFrame(LiveData.Frame frame) {
        ApplicationService service = ctx.service;
        chip.set(frame.state());
        updated.setText("Updated " + Fmt.clock(frame.timeMillis()));

        List<VehicleInfo> vehicles = frame.vehicles();
        int active = 0, stale = 0, paused = 0, idle = 0, moving = 0;
        double speedSum = 0;
        for (VehicleInfo v : vehicles) {
            switch (v.status()) {
                case "Active" -> active++;
                case "Stale" -> stale++;
                case "Paused" -> paused++;
                default -> idle++;
            }
            if (v.speedKmh() > 0.5) { moving++; speedSum += v.speedKmh(); }
        }
        int total = vehicles.size();
        activeBar.set(active, total);
        staleBar.set(stale, total);
        pausedBar.set(paused, total);
        idleBar.set(idle, total);
        statusNote.setText(total == 0
                ? "No vehicles are being tracked. Start the simulation from the Live Map."
                : "Stale = no update for " + Fmt.decimal(service.getStaleThresholdMs() / 1000.0, 1) + " s while running.");

        int configured = service.getTotalVehicleCount();
        vehiclesTile.set(Fmt.count(total), "of " + Fmt.count(configured) + " configured · " + Fmt.count(active) + " active");
        speedTile.set(moving == 0 ? "–" : Fmt.decimal(speedSum / moving, 1) + " km/h",
                moving == 0 ? "No vehicles moving" : "Mean of " + Fmt.count(moving) + " moving vehicles");

        MetricsSample m = frame.metrics();
        if (m != null) {
            simTile.set(switch (m.state()) {
                case RUNNING -> "Running";
                case PAUSED -> "Paused";
                case STOPPED -> "Stopped";
            }, "Sim time " + Fmt.duration(m.simulatedSeconds()) + " · " + Fmt.decimal(service.getSimulationSpeed(), 1) + "× speed");
            rateTile.set(Fmt.rate(m.generatedRate()) + "/s", "Indexed " + Fmt.rate(m.indexedRate()) + "/s · total " + Fmt.count(m.totalGenerated()));
            long events = m.geofenceEnterEvents() + m.geofenceExitEvents();
            eventsTile.set(Fmt.count(events), Fmt.count(m.geofenceEnterEvents()) + " enter · " + Fmt.count(m.geofenceExitEvents()) + " exit");
            latencyTile.set(m.state() == ApplicationService.SimulationState.RUNNING ? Fmt.millis(m.queueLatencyMeanMs()) : "–",
                    "Queue → index, mean · max " + (m.state() == ApplicationService.SimulationState.RUNNING ? m.queueLatencyMaxMs() + " ms" : "–"));

            List<MetricsSample> history = ctx.liveData().history();
            long[] t = new long[history.size()];
            double[][] v = new double[history.size()][1];
            for (int i = 0; i < history.size(); i++) {
                t[i] = history.get(i).wallTimeMillis();
                v[i][0] = history.get(i).generatedRate();
            }
            throughput.update(t, v, m.wallTimeMillis());

            shardLine.setText(service.getIndexers().length + " shards · queue depth " + Fmt.count(m.queueDepth()));
            snapshotLine.setText(m.snapshotAgeMs() < 0 ? "No snapshot yet" : Fmt.age(m.snapshotAgeMs()) + " since last publish");
            heapLine.setText(Fmt.megabytes(m.heapUsedBytes()) + " used of " + Fmt.megabytes(m.heapMaxBytes()) + " max");
            double frac = m.heapMaxBytes() > 0 ? (double) m.heapUsedBytes() / m.heapMaxBytes() : 0;
            heapBar.setProgress(frac);
            heapBar.getStyleClass().remove("warn");
            if (frac > 0.8) heapBar.getStyleClass().add("warn");
            failedLine.setText(m.failedUpdates() == 0 ? "None" : Fmt.count(m.failedUpdates()) + " updates rejected");
            tilesLine.setText(tileSummary());
        }

        if (frame.timeMillis() - lastEventsRefresh > 900) {
            lastEventsRefresh = frame.timeMillis();
            refreshEvents();
        }
    }

    private String tileSummary() {
        String json = ctx.map.getStatusJson();
        if (json == null) return "Map not loaded yet";
        long loaded = extract(json, "\"loaded\":");
        long errors = extract(json, "\"errors\":");
        boolean fallback = json.contains("\"fallback\":true");
        boolean offline = json.contains("\"basemap\":\"offline\"");
        if (offline) return "Offline basemap selected (bundled road network)";
        String s = Fmt.count(loaded) + " loaded · " + Fmt.count(errors) + " failed";
        if (fallback) s += " · using offline fallback";
        return s;
    }

    static long extract(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return 0;
        int start = i + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void refreshEvents() {
        List<ZoneEvent> events = ctx.service.getEventHistory();
        eventsList.getChildren().clear();
        int shown = 0;
        for (int i = events.size() - 1; i >= 0 && shown < 7; i--, shown++) {
            ZoneEvent ev = events.get(i);
            boolean enter = ev.type() == ZoneEvent.EventType.ENTER;
            Label type = Ui.pill(enter ? "ENTER" : "EXIT", enter ? "ok" : "neutral");
            type.setMinWidth(52);
            type.setAlignment(Pos.CENTER);
            Label text = Ui.label("Vehicle " + ev.vehicleId() + (enter ? " entered " : " left ") + ctx.service.zoneDisplayName(ev.zoneId()));
            Label time = Ui.label(Fmt.clock(ev.timestamp()), "muted");
            HBox row = new HBox(10, type, text, Ui.hSpacer(), time);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(5, 0, 5, 0));
            eventsList.getChildren().add(row);
        }
        eventsEmpty.setVisible(events.isEmpty());
        eventsEmpty.setManaged(events.isEmpty());
    }

    /** One labelled horizontal bar: label, count and share of the fleet. */
    private static final class StatusBar extends VBox {
        private final Label count = Ui.label("0", "kv-value");
        private final Region fill = new Region();
        private final StackPane track = new StackPane(fill);
        private double fraction = 0;

        StatusBar(String name, String kind) {
            super(4);
            HBox top = new HBox(8, Ui.label(name, "kv-key"), Ui.hSpacer(), count);
            fill.getStyleClass().addAll("status-bar-fill", kind);
            track.getStyleClass().add("status-bar-track");
            track.setMinHeight(8);
            track.setPrefHeight(8);
            track.setMaxHeight(8);
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            track.widthProperty().addListener((obs, o, w) -> fill.setMaxWidth(w.doubleValue() * fraction));
            getChildren().addAll(top, track);
        }

        void set(int n, int total) {
            fraction = total == 0 ? 0 : (double) n / total;
            count.setText(Fmt.count(n) + (total == 0 ? "" : "  (" + Math.round(fraction * 100) + "%)"));
            fill.setMaxWidth(track.getWidth() * fraction);
            fill.setPrefWidth(track.getWidth() * fraction);
        }
    }
}
