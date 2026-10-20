package com.geotracker.fx;

import com.geotracker.fx.ui.Fmt;
import com.geotracker.fx.ui.Icons;
import com.geotracker.fx.ui.Ui;
import com.geotracker.service.ApplicationService;
import com.geotracker.service.ApplicationService.RoutePlan;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;


/** A* route planning on the OSM road graph, with endpoints picked on the map or typed in. */
public class RoutingPane extends MapWorkspace implements Page {

    private final AppContext ctx;
    private final TextField originLat = coordField("47.648800");
    private final TextField originLng = coordField("-122.334200");
    private final TextField destLat = coordField("47.650400");
    private final TextField destLng = coordField("-122.331000");
    private final ToggleGroup pickGroup = new ToggleGroup();
    private final ToggleButton pickOrigin = pickButton();
    private final ToggleButton pickDest = pickButton();
    private final Ui.Message message = new Ui.Message();
    private final CheckBox showGraph = new CheckBox("Show routable road graph");

    private final VBox resultBox = new VBox(10);
    private final Label distance = Ui.label("–", "stat-value");
    private final Label travel = Ui.label("", "stat-caption");
    private final GridPane details = Ui.keyValueGrid();
    private final Label nodes, compute, snapA, snapB, check;
    private RoutePlan lastPlan;

    public RoutingPane(AppContext ctx) {
        super("Routing", "Shortest paths on the road network (A*)");
        this.ctx = ctx;

        pickOrigin.setToggleGroup(pickGroup);
        pickDest.setToggleGroup(pickGroup);
        pickGroup.selectedToggleProperty().addListener((obs, o, sel) -> updatePickMode());

        Button swap = Ui.button("Swap", Icons.SWAP, "ghost");
        swap.setOnAction(e -> {
            String a = originLat.getText(), b = originLng.getText();
            originLat.setText(destLat.getText());
            originLng.setText(destLng.getText());
            destLat.setText(a);
            destLng.setText(b);
            showEndpoints();
        });
        Button compute = Ui.button("Compute route", Icons.ROUTE, "primary");
        compute.setDefaultButton(true);
        compute.setOnAction(e -> computeRoute());
        Button clear = Ui.button("Clear", Icons.CLOSE);
        clear.setOnAction(e -> clearRoute());

        HBox actions = new HBox(8, compute, clear, Ui.hSpacer(), swap);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox form = new VBox(12,
                endpointRow("Origin", "A", "origin", originLat, originLng, pickOrigin),
                endpointRow("Destination", "B", "destination", destLat, destLng, pickDest),
                Ui.label("Click the pin buttons, then click the map. Points snap to the nearest road-graph intersection (max 500 m).", "note"),
                actions, message);

        nodes = Ui.addRow(details, "Path");
        this.compute = Ui.addRow(details, "A* time");
        snapA = Ui.addRow(details, "Origin snap");
        snapB = Ui.addRow(details, "Destination snap");
        check = Ui.addRow(details, "Optimality");
        check.setWrapText(true);
        resultBox.getChildren().addAll(section("Route"), new VBox(2, distance, travel), details);
        resultBox.setVisible(false);
        resultBox.setManaged(false);

        ApplicationService.RoadNetworkStats stats = ctx.service.getRoadNetworkStats();
        showGraph.setOnAction(e -> ctx.map.setLayerVisible("roadGraph", showGraph.isSelected()));
        VBox graphBox = new VBox(8, section("Road network"),
                Ui.label(Fmt.count(stats.nodes()) + " nodes · " + Fmt.count(stats.directedEdges())
                        + " directed edges · " + Fmt.count(stats.oneWayEdges()) + " one-way · "
                        + Fmt.decimal(stats.roadKm(), 0) + " km of road", "secondary"),
                Ui.label("Derived from OpenStreetMap. Edge weights are haversine lengths in metres, so the A* "
                        + "heuristic (straight-line distance) is admissible.", "note"),
                showGraph);

        addToPanel(form, divider(), resultBox, divider(), graphBox);
        for (TextField f : new TextField[]{originLat, originLng, destLat, destLng}) {
            f.textProperty().addListener((obs, o, n) -> f.getStyleClass().remove("invalid"));
        }
    }

    private static TextField coordField(String value) {
        TextField f = new TextField(value);
        f.setPrefColumnCount(9);
        return f;
    }

    private static ToggleButton pickButton() {
        ToggleButton b = new ToggleButton();
        b.setGraphic(Icons.icon(Icons.PLACE, 16));
        b.getStyleClass().add("icon-button");
        b.setTooltip(new Tooltip("Pick on the map"));
        return b;
    }

    private Node endpointRow(String name, String letter, String cls, TextField lat, TextField lng, ToggleButton pick) {
        Label badge = new Label(letter);
        badge.getStyleClass().addAll("pill", "origin".equals(cls) ? "ok" : "bad");
        HBox title = new HBox(8, badge, Ui.label(name, "field-label"));
        title.setAlignment(Pos.CENTER_LEFT);
        lat.setPromptText("Latitude");
        lng.setPromptText("Longitude");
        HBox.setHgrow(lat, Priority.ALWAYS);
        HBox.setHgrow(lng, Priority.ALWAYS);
        HBox inputs = new HBox(6, lat, lng, pick);
        inputs.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, title, inputs);
    }

    @Override public String title() { return "Routing"; }
    @Override public String icon() { return Icons.ROUTE; }
    @Override public Node view() { return this; }
    @Override public boolean usesMap() { return true; }

    @Override
    public void onShow() {
        ctx.map.attachTo(mapHost);
        ctx.map.setLayerVisible("vehicles", true);
        ctx.map.setLayerVisible("zones", false);
        ctx.map.setLayerVisible("route", true);
        ctx.map.setLayerVisible("search", false);
        ctx.map.setLayerVisible("draft", false);
        ctx.map.setLayerVisible("roadGraph", showGraph.isSelected());
        ctx.map.setOnPick(this::onPick);
        ctx.map.setOnEscape(() -> pickGroup.selectToggle(null));
        updatePickMode();
        if (lastPlan == null) showEndpoints();
    }

    @Override
    public void onHide() {
        pickGroup.selectToggle(null);
        ctx.map.setPickMode(null);
        hideHint();
    }

    private void updatePickMode() {
        Toggle t = pickGroup.getSelectedToggle();
        if (t == pickOrigin) {
            ctx.map.setPickMode("origin");
            showHint("Click the map to set the origin  ·  Esc to cancel");
        } else if (t == pickDest) {
            ctx.map.setPickMode("destination");
            showHint("Click the map to set the destination  ·  Esc to cancel");
        } else {
            ctx.map.setPickMode(null);
            hideHint();
        }
    }

    private void onPick(String mode, double[] latLng) {
        if ("origin".equals(mode)) {
            originLat.setText(Fmt.coord(latLng[0]));
            originLng.setText(Fmt.coord(latLng[1]));
            // Natural flow: after the origin, pick the destination next.
            pickGroup.selectToggle(pickDest);
        } else if ("destination".equals(mode)) {
            destLat.setText(Fmt.coord(latLng[0]));
            destLng.setText(Fmt.coord(latLng[1]));
            pickGroup.selectToggle(null);
            computeRoute();
            return;
        }
        lastPlan = null;
        showEndpoints();
    }

    private double[] read(TextField lat, TextField lng) {
        double[] v = new double[2];
        try {
            v[0] = Double.parseDouble(lat.getText().trim());
        } catch (NumberFormatException e) {
            lat.getStyleClass().add("invalid");
            return null;
        }
        try {
            v[1] = Double.parseDouble(lng.getText().trim());
        } catch (NumberFormatException e) {
            lng.getStyleClass().add("invalid");
            return null;
        }
        if (v[0] < -90 || v[0] > 90) { lat.getStyleClass().add("invalid"); return null; }
        if (v[1] < -180 || v[1] > 180) { lng.getStyleClass().add("invalid"); return null; }
        return v;
    }

    private void showEndpoints() {
        double[] a = quietRead(originLat, originLng);
        double[] b = quietRead(destLat, destLng);
        StringBuilder sb = new StringBuilder("{");
        if (a != null) MapPayloads.latLng(sb.append("\"origin\":"), a[0], a[1]);
        if (b != null) MapPayloads.latLng(sb.append(a != null ? ",\"destination\":" : "\"destination\":"), b[0], b[1]);
        ctx.map.setRoute(sb.append('}').toString());
    }

    private static double[] quietRead(TextField lat, TextField lng) {
        try {
            double la = Double.parseDouble(lat.getText().trim());
            double ln = Double.parseDouble(lng.getText().trim());
            if (Math.abs(la) > 90 || Math.abs(ln) > 180) return null;
            return new double[]{la, ln};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void computeRoute() {
        message.clear();
        double[] a = read(originLat, originLng);
        double[] b = read(destLat, destLng);
        if (a == null || b == null) {
            message.show("bad", "Enter a valid latitude (−90 to 90) and longitude (−180 to 180) for both points.");
            return;
        }
        RoutePlan plan = ctx.service.planRoute(a[1], a[0], b[1], b[0]);
        lastPlan = plan;
        if (!plan.found()) {
            resultBox.setVisible(false);
            resultBox.setManaged(false);
            message.show("bad", plan.message());
            showEndpoints();
            return;
        }
        if (plan.nodeCount() == 1) {
            message.show("info", "Both points snap to the same intersection, so the route has zero length.");
        }

        StringBuilder sb = new StringBuilder("{\"path\":").append(MapPayloads.pathFromLngLat(plan.pathLngLat()));
        MapPayloads.latLng(sb.append(",\"origin\":"), a[0], a[1]);
        MapPayloads.latLng(sb.append(",\"destination\":"), b[0], b[1]);
        MapPayloads.latLng(sb.append(",\"originSnap\":"), plan.originNodeLngLat()[1], plan.originNodeLngLat()[0]);
        MapPayloads.latLng(sb.append(",\"destinationSnap\":"), plan.destinationNodeLngLat()[1], plan.destinationNodeLngLat()[0]);
        sb.append(",\"fit\":true}");
        ctx.map.setRoute(sb.toString());

        distance.setText(Fmt.meters(plan.distanceMeters()));
        travel.setText("≈ " + Fmt.decimal(plan.travelSeconds() / 60.0, 1) + " min at posted speed limits (no traffic)");
        nodes.setText(Fmt.count(plan.nodeCount()) + " road nodes · " + Fmt.count(Math.max(0, plan.nodeCount() - 1)) + " segments");
        compute.setText(Fmt.nanosAsMillis(plan.computeNanos()));
        snapA.setText(Fmt.meters(plan.originSnapMeters()) + " to nearest intersection");
        snapB.setText(Fmt.meters(plan.destinationSnapMeters()) + " to nearest intersection");
        if (plan.matchesReference()) {
            check.setText("✓ Same cost as Dijkstra reference (" + Fmt.decimal(plan.dijkstraCost(), 1) + " m)");
            check.setStyle("-fx-text-fill: #137333;");
        } else {
            check.setText("Differs from Dijkstra: A* " + Fmt.decimal(plan.astarCost(), 3) + " m vs " + Fmt.decimal(plan.dijkstraCost(), 3) + " m");
            check.setStyle("-fx-text-fill: #c5221f;");
        }
        resultBox.setVisible(true);
        resultBox.setManaged(true);
    }

    private void clearRoute() {
        lastPlan = null;
        message.clear();
        resultBox.setVisible(false);
        resultBox.setManaged(false);
        ctx.map.setRoute("{}");
    }

    /** Programmatic entry point (used by the UI tour). */
    public void computeRouteForTour() {
        computeRoute();
    }

    /** UI tour: arm "pick origin on map", as if the pin button had been pressed. */
    void beginPickingForTour() {
        pickGroup.selectToggle(pickOrigin);
    }
}
