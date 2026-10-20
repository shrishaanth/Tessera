package com.geotracker.fx;

import com.geotracker.fx.ui.Fmt;
import com.geotracker.fx.ui.Icons;
import com.geotracker.fx.ui.Ui;
import com.geotracker.service.ApplicationService;
import com.geotracker.service.ApplicationService.SimulationState;
import com.geotracker.service.ApplicationService.TrajectorySource;
import com.geotracker.util.Config;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Settings that change behaviour, plus data provenance and attribution. */
public class SettingsPane extends ScrollPane implements Page {

    private final AppContext ctx;
    private final ToggleGroup basemapGroup = new ToggleGroup();
    private final RadioButton osm = new RadioButton("OpenStreetMap Standard tiles (online)");
    private final RadioButton offline = new RadioButton("Offline road network (bundled, no network needed)");
    private final Label tileStatus = Ui.label("", "kv-value");
    private final ToggleGroup sourceGroup = new ToggleGroup();
    private final RadioButton roadGraph = new RadioButton(TrajectorySource.ROAD_GRAPH.label());
    private final RadioButton csv = new RadioButton(TrajectorySource.CSV_REPLAY.label());
    private final Label sourceLock = Ui.label("", "note");
    private final Spinner<Double> stale = new Spinner<>(0.5, 30.0, 2.0, 0.5);

    public SettingsPane(AppContext ctx) {
        this.ctx = ctx;
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("page-scroll");
        ApplicationService service = ctx.service;

        // --- Map
        osm.setToggleGroup(basemapGroup);
        offline.setToggleGroup(basemapGroup);
        osm.setSelected(true);
        basemapGroup.selectedToggleProperty().addListener((obs, o, n) ->
                ctx.map.setBasemap(n == offline ? "offline" : "osm"));
        Button retry = Ui.button("Retry tiles", Icons.RESET);
        retry.setOnAction(e -> ctx.map.retryTiles());
        HBox tileRow = new HBox(12, Ui.label("Tile status", "kv-key"), tileStatus, Ui.hSpacer(), retry);
        tileRow.setAlignment(Pos.CENTER_LEFT);
        VBox mapCard = Ui.card("Map", "Basemap shown behind vehicles, routes and zones",
                osm, Ui.label("Tiles from tile.openstreetmap.org under the OSM tile usage policy: the app identifies itself "
                        + "in its User-Agent, shows attribution, and retries a failed tile at most three times. If tiles keep "
                        + "failing, the map switches to the bundled road network automatically and says so.", "note"),
                offline, tileRow);

        // --- Simulation
        roadGraph.setToggleGroup(sourceGroup);
        csv.setToggleGroup(sourceGroup);
        (service.getTrajectorySource() == TrajectorySource.CSV_REPLAY ? csv : roadGraph).setSelected(true);
        sourceGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            TrajectorySource s = n == csv ? TrajectorySource.CSV_REPLAY : TrajectorySource.ROAD_GRAPH;
            service.setTrajectorySource(s);
            if (service.getTrajectorySource() != s) {
                // Rejected (simulation running): restore the radio button.
                (service.getTrajectorySource() == TrajectorySource.CSV_REPLAY ? csv : roadGraph).setSelected(true);
            }
            ctx.simulationChanged();
        });
        stale.setEditable(true);
        stale.setPrefWidth(100);
        stale.getValueFactory().setValue(service.getStaleThresholdMs() / 1000.0);
        stale.valueProperty().addListener((obs, o, n) -> {
            if (n != null) service.setStaleThresholdMs(Math.round(n * 1000));
        });
        HBox staleRow = new HBox(10, Ui.label("Mark a vehicle stale after", "kv-key"), stale, Ui.label("seconds without an update", "kv-key"));
        staleRow.setAlignment(Pos.CENTER_LEFT);
        VBox simCard = Ui.card("Simulation", "Where vehicle trajectories come from",
                roadGraph,
                Ui.label("Every vehicle drives trips across the central-Seattle road network: it starts at a random "
                        + "point on a street, picks a destination 1.5–7 km away, follows a shortest path there "
                        + "(preferring arterials, obeying one-way streets) at 85–100% of the posted limit, then picks "
                        + "the next destination. Seeded, so runs are repeatable. Synthetic trips, not recorded "
                        + "traffic: no congestion, signals or turn delays.", "note"),
                csv,
                Ui.label("Vehicles 0–19 replay the bundled CSV file. That dataset was generated on a regular 10 × 10 "
                        + "lattice (about 33 m × 49 m cells) and does not follow real streets — on the map those vehicles "
                        + "cross blocks, the park and the lake. Remaining vehicles use the road graph.", "note"),
                sourceLock, new Separator(), staleRow);

        // --- Data & attribution
        GridPane data = Ui.keyValueGrid();
        Ui.addRow(data, "Area").setText(Config.CITY_AREA_NAME + " — Ballard/Interbay to Montlake/University District, "
                + "Pioneer Square to Green Lake (about 9 × 10.6 km)");
        Ui.addRow(data, "Map extent").setText(String.format("%.4f to %.4f lat, %.4f to %.4f lng",
                service.getDatasetBounds().minY(), service.getDatasetBounds().maxY(),
                service.getDatasetBounds().minX(), service.getDatasetBounds().maxX()));
        ApplicationService.RoadNetworkStats net = service.getRoadNetworkStats();
        Ui.addRow(data, "Road graph").setText(Fmt.count(net.nodes()) + " nodes, " + Fmt.decimal(net.roadKm(), 0)
                + " km of drivable road (motorway to residential; no footpaths, trails or service roads), "
                + "OpenStreetMap snapshot 2026-10-16 (© OpenStreetMap contributors, ODbL)");
        Ui.addRow(data, "CSV dataset").setText(Config.TRAJECTORY_RESOURCE + " — 20 synthetic trajectories, 200 ms sampling");
        Ui.addRow(data, "Update rate").setText(Fmt.decimal(Config.UPDATE_RATE_HZ, 0) + " Hz per vehicle (simulated clock)");
        Ui.addRow(data, "Index").setText(Config.SHARDS + " shards · copy-on-write quadtree + HAMT · publish every "
                + Config.PUBLISH_INTERVAL_MS + " ms or " + Config.PUBLISH_MAX_DIRTY + " updates");
        Ui.addRow(data, "Map library").setText("Leaflet 1.9.4 (BSD-2-Clause), bundled");
        Ui.addRow(data, "Tiles").setText("© OpenStreetMap contributors (tile.openstreetmap.org)");
        Ui.addRow(data, "Icons").setText("Material Design icons (Apache License 2.0)");
        VBox dataCard = Ui.card("Data and attribution", "Where the data and map come from", data);
        Hyperlink copyright = new Hyperlink("openstreetmap.org/copyright");
        copyright.setOnAction(e -> ctx.openLink("https://www.openstreetmap.org/copyright"));
        dataCard.getChildren().add(copyright);

        // --- System
        GridPane sys = Ui.keyValueGrid();
        Ui.addRow(sys, "Java").setText(System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        Ui.addRow(sys, "JavaFX").setText(System.getProperty("javafx.runtime.version", "unknown"));
        Ui.addRow(sys, "OS").setText(System.getProperty("os.name") + " " + System.getProperty("os.version") + " · " + System.getProperty("os.arch"));
        Ui.addRow(sys, "Processors").setText(String.valueOf(Runtime.getRuntime().availableProcessors()));
        Ui.addRow(sys, "Max heap").setText(Fmt.megabytes(Runtime.getRuntime().maxMemory()));
        VBox sysCard = Ui.card("System", sys);

        VBox column = new VBox(16, mapCard, simCard, dataCard, sysCard);
        column.setMaxWidth(860);
        VBox page = new VBox(Ui.pageHeader("Settings", "Map, simulation and data sources"), column);
        page.getStyleClass().add("page");
        setContent(page);

        ctx.onSimulationChanged(this::refreshLock);
        refreshLock();
    }

    private void refreshLock() {
        boolean stopped = ctx.service.getSimulationState() == SimulationState.STOPPED;
        roadGraph.setDisable(!stopped);
        csv.setDisable(!stopped);
        sourceLock.setText(stopped ? "Changing the source clears tracked positions."
                : "Stop the simulation to change the trajectory source.");
        (ctx.service.getTrajectorySource() == TrajectorySource.CSV_REPLAY ? csv : roadGraph).setSelected(true);
    }

    @Override public String title() { return "Settings"; }
    @Override public String icon() { return Icons.TUNE; }
    @Override public Node view() { return this; }

    @Override
    public void onShow() {
        refreshLock();
        updateTiles();
    }

    @Override
    public void onFrame(LiveData.Frame frame) {
        if (frame.metrics() != null) updateTiles();
    }

    private void updateTiles() {
        String json = ctx.map.getStatusJson();
        if (json == null) {
            tileStatus.setText("Map still loading");
            return;
        }
        long loaded = DashboardPane.extract(json, "\"loaded\":");
        long errors = DashboardPane.extract(json, "\"errors\":");
        long retries = DashboardPane.extract(json, "\"retries\":");
        String text = Fmt.count(loaded) + " loaded · " + Fmt.count(errors) + " failed · " + Fmt.count(retries) + " retried";
        if (json.contains("\"fallback\":true")) text += " · offline fallback active";
        String diag = ctx.map.getTileDiagnostic();
        if (diag != null && !diag.isEmpty()) text += " · last error: " + diag;
        tileStatus.setText(text);
    }
}
