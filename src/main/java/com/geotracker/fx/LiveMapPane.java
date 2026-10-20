package com.geotracker.fx;

import com.geotracker.fx.ui.Fmt;
import com.geotracker.fx.ui.Icons;
import com.geotracker.fx.ui.Ui;
import com.geotracker.routing.GeoUtils;
import com.geotracker.service.ApplicationService.VehicleInfo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

/** The main live map: simulation toolbar, full-size map, layer card and selected-vehicle card. */
public class LiveMapPane extends BorderPane implements Page {

    private final AppContext ctx;
    private final StackPane mapHost = new StackPane();
    private final SimulationControlPane toolbar;

    private final CheckBox vehiclesBox = new CheckBox("Vehicles");
    private final CheckBox zonesBox = new CheckBox("Geofences");
    private final CheckBox routeBox = new CheckBox("Planned route");
    private final CheckBox roadGraphBox = new CheckBox("Road graph (routing network)");

    private final Label infoVehicles = new Label();
    private final Label infoRefresh = new Label();
    private final VBox vehicleCard;
    private final Label vTitle = Ui.label("", "vehicle-card-title");
    private final Label vSource = Ui.pill("", "info");
    private final GridPane vGrid = Ui.keyValueGrid();
    private final Label vStatus, vSpeed, vHeading, vPosition, vUpdated;
    private final ToggleButton followBtn = new ToggleButton("Follow");
    private long lastZoneSync = 0;
    private boolean userCollapsedLayers = false;

    public LiveMapPane(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("page-flush");
        toolbar = new SimulationControlPane(ctx);
        setTop(toolbar);

        mapHost.getStyleClass().add("map-host");
        mapHost.setMinSize(0, 0);
        setCenter(mapHost);

        // --- layers & legend card (top right)
        vehiclesBox.setSelected(true);
        zonesBox.setSelected(true);
        routeBox.setSelected(true);
        roadGraphBox.setSelected(false);
        vehiclesBox.setOnAction(e -> ctx.map.setLayerVisible("vehicles", vehiclesBox.isSelected()));
        zonesBox.setOnAction(e -> ctx.map.setLayerVisible("zones", zonesBox.isSelected()));
        routeBox.setOnAction(e -> ctx.map.setLayerVisible("route", routeBox.isSelected()));
        roadGraphBox.setOnAction(e -> ctx.map.setLayerVisible("roadGraph", roadGraphBox.isSelected()));

        VBox legend = new VBox(5,
                legendRow(dot("#2a78d6"), "Road-graph vehicle"),
                legendRow(dot("#eb6834"), "CSV replay vehicle"),
                legendRow(dot("#9aa0a6"), "Stale (no recent update)"),
                legendRow(swatch("#1baf7a"), "Geofence"),
                legendRow(routeLine(), "Route"));
        legend.setPadding(new Insets(0, 0, 0, 24));

        HBox layersHead = new HBox(8, Icons.icon(Icons.LAYERS, 16), Ui.label("Map layers", "map-overlay-title"));
        layersHead.setAlignment(Pos.CENTER_LEFT);
        VBox layersBody = new VBox(8, vehiclesBox, legend, zonesBox, routeBox, roadGraphBox,
                Ui.label("Basemap © OpenStreetMap contributors", "muted"));
        ToggleButton collapse = new ToggleButton();
        collapse.getStyleClass().addAll("icon-button", "ghost");
        collapse.setGraphic(Icons.icon(Icons.CLOSE, 14));
        collapse.setTooltip(new Tooltip("Hide layer panel"));
        layersHead.getChildren().addAll(Ui.hSpacer(), collapse);
        VBox layersCard = new VBox(10, layersHead, layersBody);
        layersCard.getStyleClass().add("map-overlay");
        layersCard.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        layersCard.setPrefWidth(250);
        Button showLayers = Ui.iconButton(Icons.LAYERS, "Show map layers");
        showLayers.getStyleClass().add("map-overlay-button");
        showLayers.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        showLayers.setVisible(false);
        collapse.setOnAction(e -> {
            userCollapsedLayers = true;
            setLayersCardVisible(layersCard, showLayers, false);
        });
        showLayers.setOnAction(e -> {
            userCollapsedLayers = false;
            setLayersCardVisible(layersCard, showLayers, true);
            collapse.setSelected(false);
        });
        // On narrow maps the card would cover too much of the map: collapse it to the button.
        mapHost.widthProperty().addListener((obs, o, w) -> {
            if (!userCollapsedLayers) setLayersCardVisible(layersCard, showLayers, w.doubleValue() >= 880);
        });
        StackPane.setAlignment(layersCard, Pos.TOP_RIGHT);
        StackPane.setMargin(layersCard, new Insets(12, 12, 0, 0));
        StackPane.setAlignment(showLayers, Pos.TOP_RIGHT);
        StackPane.setMargin(showLayers, new Insets(12, 12, 0, 0));

        // --- info pill (top left)
        HBox infoPill = new HBox(10, Icons.icon(Icons.CAR, 16), infoVehicles, new Separator(javafx.geometry.Orientation.VERTICAL), infoRefresh);
        infoPill.getStyleClass().add("map-info-pill");
        infoPill.setAlignment(Pos.CENTER_LEFT);
        infoPill.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        infoPill.setMouseTransparent(true);
        StackPane.setAlignment(infoPill, Pos.TOP_LEFT);
        StackPane.setMargin(infoPill, new Insets(12, 0, 0, 12));

        // --- selected vehicle card (bottom left, above the scale bar)
        vStatus = Ui.addRow(vGrid, "Status");
        vSpeed = Ui.addRow(vGrid, "Speed");
        vHeading = Ui.addRow(vGrid, "Heading");
        vPosition = Ui.addRow(vGrid, "Position");
        vUpdated = Ui.addRow(vGrid, "Last update");
        vPosition.getStyleClass().add("mono");
        Button close = Ui.iconButton(Icons.CLOSE, "Clear selection", "ghost");
        close.setOnAction(e -> ctx.selectedVehicle.set(-1));
        HBox vHead = new HBox(8, vTitle, vSource, Ui.hSpacer(), close);
        vHead.setAlignment(Pos.CENTER_LEFT);
        Button centre = Ui.button("Center", Icons.LOCATE);
        centre.setOnAction(e -> ctx.map.selectVehicle(ctx.selectedVehicle.get(), true));
        followBtn.setGraphic(Icons.icon(Icons.NAVIGATION, 16));
        followBtn.setTooltip(new Tooltip("Keep the map centred on this vehicle"));
        followBtn.setOnAction(e -> ctx.map.followVehicle(followBtn.isSelected() ? ctx.selectedVehicle.get() : -1));
        Button details = Ui.button("Details", Icons.CAR, "ghost");
        details.setOnAction(e -> ctx.navigate("Vehicles"));
        for (Region b : new Region[]{centre, followBtn, details}) b.setMinWidth(Region.USE_PREF_SIZE);
        HBox vActions = new HBox(8, centre, followBtn, Ui.hSpacer(), details);
        vehicleCard = new VBox(10, vHead, vGrid, vActions);
        vehicleCard.getStyleClass().add("map-overlay");
        vehicleCard.setPrefWidth(330);
        vehicleCard.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        vehicleCard.setVisible(false);
        StackPane.setAlignment(vehicleCard, Pos.BOTTOM_LEFT);
        StackPane.setMargin(vehicleCard, new Insets(0, 0, 40, 12));

        mapHost.getChildren().addAll(infoPill, layersCard, showLayers, vehicleCard);

        ctx.selectedVehicle.addListener((obs, o, n) -> {
            if (n.longValue() < 0) {
                followBtn.setSelected(false);
                ctx.map.followVehicle(-1);
            } else if (followBtn.isSelected()) {
                ctx.map.followVehicle(n.longValue());
            }
            LiveData.Frame f = ctx.lastFrame.get();
            if (f != null) updateVehicleCard(f);
        });
    }

    private static void setLayersCardVisible(Node card, Node button, boolean visible) {
        card.setVisible(visible);
        button.setVisible(!visible);
    }

    private static HBox legendRow(Node mark, String text) {
        StackPane m = new StackPane(mark);
        m.setMinWidth(16);
        HBox row = new HBox(8, m, Ui.label(text, "small"));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Node dot(String color) {
        Circle c = new Circle(5, Color.web(color));
        c.getStyleClass().add("legend-dot");
        return c;
    }

    private static Node swatch(String color) {
        Region r = new Region();
        r.setStyle("-fx-background-color: " + color + "33; -fx-border-color: " + color + "; -fx-border-width: 2; -fx-border-radius: 3; -fx-background-radius: 3;");
        r.setMinSize(14, 11);
        r.setMaxSize(14, 11);
        return r;
    }

    private static Node routeLine() {
        Line l = new Line(0, 0, 14, 0);
        l.setStroke(Color.web("#6250d6"));
        l.setStrokeWidth(4);
        return l;
    }

    @Override public String title() { return "Live Map"; }
    @Override public String icon() { return Icons.MAP; }
    @Override public Node view() { return this; }
    @Override public boolean usesMap() { return true; }

    @Override
    public void onShow() {
        ctx.map.attachTo(mapHost);
        ctx.map.setPickMode(null);
        ctx.map.setLayerVisible("vehicles", vehiclesBox.isSelected());
        ctx.map.setLayerVisible("zones", zonesBox.isSelected());
        ctx.map.setLayerVisible("route", routeBox.isSelected());
        ctx.map.setLayerVisible("roadGraph", roadGraphBox.isSelected());
        ctx.map.setLayerVisible("search", false);
        ctx.map.setLayerVisible("draft", false);
        ctx.map.highlightZone(null);
        ctx.map.selectVehicle(ctx.selectedVehicle.get(), false);
        ctx.syncZonesToMap(true);
        toolbar.refresh();
    }

    @Override
    public void onFrame(LiveData.Frame frame) {
        int shown = vehiclesBox.isSelected() ? frame.vehicles().size() : 0;
        int configured = ctx.service.getTotalVehicleCount();
        if (frame.vehicles().isEmpty() && frame.state() == com.geotracker.service.ApplicationService.SimulationState.STOPPED) {
            infoVehicles.setText("No vehicles yet — press Start to run " + Fmt.count(configured));
        } else {
            infoVehicles.setText(Fmt.count(shown) + " of " + Fmt.count(configured) + " vehicles on map");
        }
        infoRefresh.setText("refresh " + (LiveData.intervalFor(configured) >= 1000 ? "1 s" : LiveData.intervalFor(configured) + " ms"));
        if (frame.metrics() != null && zonesBox.isSelected() && frame.timeMillis() - lastZoneSync > 1500) {
            lastZoneSync = frame.timeMillis();
            ctx.syncZonesToMap(false);
        }
        updateVehicleCard(frame);
    }

    private void updateVehicleCard(LiveData.Frame frame) {
        long id = ctx.selectedVehicle.get();
        VehicleInfo v = id < 0 ? null : AppContext.find(frame.vehicles(), id);
        if (v == null) {
            vehicleCard.setVisible(false);
            return;
        }
        vehicleCard.setVisible(true);
        vTitle.setText("Vehicle " + v.id());
        vSource.setText(AppContext.sourceLabel(v.source()));
        vSource.getStyleClass().removeAll("info", "orange");
        vSource.getStyleClass().add("csv".equals(v.source()) ? "orange" : "info");
        vStatus.setText(v.status());
        vSpeed.setText(Fmt.decimal(v.speedKmh(), 1) + " km/h");
        vHeading.setText(v.headingDeg() < 0 ? "–" : Fmt.decimal(v.headingDeg(), 0) + "° " + GeoUtils.cardinal(v.headingDeg()));
        vPosition.setText(Fmt.latLng(v.y(), v.x()));
        vUpdated.setText(Fmt.clock(v.lastUpdate()) + "  (" + Fmt.age(frame.timeMillis() - v.lastUpdate()) + " ago)");
    }
}
