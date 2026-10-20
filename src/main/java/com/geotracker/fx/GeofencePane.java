package com.geotracker.fx;

import com.geotracker.fx.ui.Fmt;
import com.geotracker.fx.ui.Icons;
import com.geotracker.fx.ui.Ui;
import com.geotracker.model.Position;
import com.geotracker.model.ZoneEvent;
import com.geotracker.routing.GeoUtils;
import com.geotracker.service.ApplicationService.ZoneView;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Geofence zones (built-in and user-drawn) and the ENTER/EXIT event log. */
public class GeofencePane extends MapWorkspace implements Page {

    private static final int EVENT_ROWS = 300;

    private final AppContext ctx;
    private final ObservableList<ZoneView> zoneItems = FXCollections.observableArrayList();
    private final ListView<ZoneView> zoneList = new ListView<>(zoneItems);
    private final Button deleteBtn = Ui.button("Delete", Icons.DELETE, "danger");
    private final Button zoomBtn = Ui.button("Zoom to", Icons.FIT);
    private final Button demoBtn = Ui.button("Send 10 vehicles here", Icons.ROUTE);
    private final Ui.Message demoMessage = new Ui.Message();

    private final TextField nameField = new TextField();
    private final ToggleButton drawBtn = new ToggleButton("Draw on map");
    private final TextArea vertexArea = new TextArea();
    private final Label vertexCount = Ui.label("0 vertices", "muted");
    private final Ui.Message message = new Ui.Message();
    private final List<double[]> draft = new ArrayList<>();
    private boolean updatingText = false;

    private final ObservableList<EventRow> eventRows = FXCollections.observableArrayList();
    private final TableView<EventRow> eventTable = new TableView<>(eventRows);
    private final ChoiceBox<String> zoneFilter = new ChoiceBox<>();
    private final Label eventSummary = Ui.label("", "muted");
    private String zoneSignature = "";
    private long lastRefresh = 0;

    public GeofencePane(AppContext ctx) {
        super("Geofencing", "Zones that raise ENTER and EXIT events");
        this.ctx = ctx;

        // --- zone list
        zoneList.setCellFactory(lv -> new ZoneCell());
        zoneList.setPrefHeight(176);
        zoneList.setPlaceholder(Ui.label("No zones", "muted"));
        zoneList.getSelectionModel().selectedItemProperty().addListener((obs, o, z) -> {
            deleteBtn.setDisable(z == null || z.builtIn());
            zoomBtn.setDisable(z == null);
            demoBtn.setDisable(z == null);
            ctx.map.highlightZone(z == null ? null : z.id());
        });
        deleteBtn.setDisable(true);
        zoomBtn.setDisable(true);
        deleteBtn.setOnAction(e -> deleteSelected());
        zoomBtn.setOnAction(e -> {
            ZoneView z = zoneList.getSelectionModel().getSelectedItem();
            if (z != null) ctx.map.fitZone(z.id());
        });
        HBox zoneActions = new HBox(8, zoomBtn, deleteBtn);
        demoBtn.setDisable(true);
        demoBtn.setOnAction(e -> runDemo());
        HBox demoRow = new HBox(8, Ui.pill("DEMO", "violet"), demoBtn);
        demoRow.setAlignment(Pos.CENTER_LEFT);
        VBox zonesBox = new VBox(10, section("Zones"), zoneList, zoneActions,
                Ui.label("Every zone is checked against all vehicles every 500 ms while the simulation runs.", "note"),
                demoRow,
                Ui.label("Demo only: redirects the 10 nearest road-graph vehicles to destinations inside the selected "
                        + "zone. They drive there on normal routes, so their ENTER/EXIT events are real detections; "
                        + "no event or count is added directly.", "note"),
                demoMessage);

        // --- create zone
        nameField.setPromptText("Zone name, e.g. Depot");
        drawBtn.setGraphic(Icons.icon(Icons.GEOFENCE, 16));
        drawBtn.setOnAction(e -> setDrawing(drawBtn.isSelected()));
        Button undo = Ui.iconButton(Icons.UNDO, "Remove last vertex", "ghost");
        undo.setOnAction(e -> {
            if (!draft.isEmpty()) draft.remove(draft.size() - 1);
            syncDraft(true);
        });
        Button clear = Ui.iconButton(Icons.CLOSE, "Clear vertices", "ghost");
        clear.setOnAction(e -> {
            draft.clear();
            syncDraft(true);
        });
        HBox drawRow = new HBox(6, drawBtn, undo, clear, Ui.hSpacer(), vertexCount);
        drawRow.setAlignment(Pos.CENTER_LEFT);
        vertexArea.setPromptText("Or type one vertex per line:\nlatitude, longitude\n47.6480, -122.3330\n…");
        vertexArea.setPrefRowCount(5);
        vertexArea.getStyleClass().add("mono");
        vertexArea.textProperty().addListener((obs, o, n) -> {
            if (!updatingText) parseVertices();
        });
        Button create = Ui.button("Create zone", Icons.ADD, "primary");
        create.setOnAction(e -> createZone());
        VBox createBox = new VBox(10, section("New zone"), Ui.label("Name", "field-label"), nameField,
                Ui.label("Vertices", "field-label"), drawRow, vertexArea, create, message);

        addToPanel(zonesBox, divider(), createBox);

        // --- events table under the map
        TableColumn<EventRow, String> time = column("Time", 110, r -> r.time);
        TableColumn<EventRow, String> vehicle = column("Vehicle", 90, r -> r.vehicle);
        TableColumn<EventRow, String> zone = column("Zone", 160, r -> r.zone);
        TableColumn<EventRow, String> type = column("Event", 90, r -> r.type);
        type.setCellFactory(c -> new TableCell<>() {
            private final Label pill = new Label();
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                pill.setText(item);
                pill.getStyleClass().setAll("pill", "ENTER".equals(item) ? "ok" : "neutral");
                setGraphic(pill);
            }
        });
        eventTable.getColumns().addAll(List.of(time, vehicle, zone, type));
        eventTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        eventTable.setPlaceholder(Ui.label("No events yet — start the simulation on the Live Map.", "muted"));
        zoneFilter.getItems().add("All zones");
        zoneFilter.getSelectionModel().select(0);
        zoneFilter.valueProperty().addListener((obs, o, n) -> refreshEvents());
        HBox eventsHead = new HBox(10, Ui.label("Geofence events", "card-title"), eventSummary, Ui.hSpacer(), zoneFilter);
        eventsHead.setAlignment(Pos.CENTER_LEFT);
        VBox events = new VBox(8, eventsHead, eventTable);
        VBox.setVgrow(eventTable, Priority.ALWAYS);
        events.getStyleClass().add("events-panel");
        events.setStyle("-fx-background-color: #ffffff; -fx-padding: 12 16 14 16;");

        // Map on top, events below.
        setCenter(null);
        SplitPane split = new SplitPane(mapHost, events);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.64);
        SplitPane.setResizableWithParent(events, false);
        setCenter(split);
    }

    private static TableColumn<EventRow, String> column(String name, double width, java.util.function.Function<EventRow, SimpleStringProperty> f) {
        TableColumn<EventRow, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cd -> f.apply(cd.getValue()));
        c.setPrefWidth(width);
        return c;
    }

    @Override public String title() { return "Geofencing"; }
    @Override public String icon() { return Icons.GEOFENCE; }
    @Override public Node view() { return this; }
    @Override public boolean usesMap() { return true; }

    @Override
    public void onShow() {
        ctx.map.attachTo(mapHost);
        ctx.map.setLayerVisible("vehicles", true);
        ctx.map.setLayerVisible("zones", true);
        ctx.map.setLayerVisible("route", false);
        ctx.map.setLayerVisible("search", false);
        ctx.map.setLayerVisible("roadGraph", false);
        ctx.map.setLayerVisible("draft", true);
        ctx.map.setOnPick(this::onPick);
        ctx.map.setOnEscape(() -> setDrawing(false));
        setDrawing(drawBtn.isSelected());
        refreshZones(true);
        syncDraft(false);
        refreshEvents();
    }

    @Override
    public void onHide() {
        setDrawing(false);
        ctx.map.highlightZone(null);
    }

    @Override
    public void onFrame(LiveData.Frame frame) {
        if (frame.timeMillis() - lastRefresh < 900) return;
        lastRefresh = frame.timeMillis();
        refreshZones(false);
        refreshEvents();
    }

    private void setDrawing(boolean on) {
        drawBtn.setSelected(on);
        ctx.map.setPickMode(on ? "zone" : null);
        if (on) showHint("Click the map to add vertices  ·  Esc to finish");
        else hideHint();
    }

    private void onPick(String mode, double[] latLng) {
        if (!"zone".equals(mode)) return;
        draft.add(latLng);
        syncDraft(true);
    }

    /** Pushes the draft to the map and (optionally) rewrites the text area from it. */
    private void syncDraft(boolean rewriteText) {
        StringBuilder json = new StringBuilder("[");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < draft.size(); i++) {
            if (i > 0) json.append(',');
            MapPayloads.latLng(json, draft.get(i)[0], draft.get(i)[1]);
            text.append(String.format(Locale.US, "%.6f, %.6f%n", draft.get(i)[0], draft.get(i)[1]));
        }
        ctx.map.setDraft(json.append(']').toString());
        vertexCount.setText(draft.size() + (draft.size() == 1 ? " vertex" : " vertices"));
        if (rewriteText) {
            updatingText = true;
            vertexArea.setText(text.toString());
            updatingText = false;
        }
    }

    /** Parses the text area into the draft. Returns an error message, or null if valid. */
    private String parseVertices() {
        List<double[]> parsed = new ArrayList<>();
        String[] lines = vertexArea.getText() == null ? new String[0] : vertexArea.getText().split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("[,;\\s]+");
            if (parts.length != 2) return "Line " + (i + 1) + ": expected \"latitude, longitude\".";
            try {
                double lat = Double.parseDouble(parts[0]);
                double lng = Double.parseDouble(parts[1]);
                if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return "Line " + (i + 1) + ": coordinate out of range.";
                parsed.add(new double[]{lat, lng});
            } catch (NumberFormatException e) {
                return "Line " + (i + 1) + ": \"" + line + "\" is not a number pair.";
            }
        }
        draft.clear();
        draft.addAll(parsed);
        syncDraft(false);
        return null;
    }

    private void createZone() {
        message.clear();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            message.show("bad", "Enter a zone name.");
            return;
        }
        for (ZoneView z : ctx.service.getZones()) {
            if (z.name().equalsIgnoreCase(name)) {
                message.show("bad", "A zone named \"" + name + "\" already exists.");
                return;
            }
        }
        String error = parseVertices();
        if (error != null) {
            message.show("bad", error);
            return;
        }
        if (draft.size() < 3) {
            message.show("bad", "A zone needs at least 3 vertices (it has " + draft.size() + ").");
            return;
        }
        List<Position> polygon = new ArrayList<>();
        for (double[] p : draft) polygon.add(new Position(p[1], p[0], 0));
        double area = GeoUtils.polygonAreaSqMeters(polygon);
        if (area < 25) {
            message.show("bad", "The polygon is degenerate (area " + Fmt.area(area) + "). Spread the vertices out.");
            return;
        }
        String id = ctx.service.getGeofenceEngine().createZone(name, polygon, Set.of(), true, true);
        setDrawing(false);
        draft.clear();
        syncDraft(true);
        nameField.clear();
        message.show("ok", "Created \"" + name + "\" (" + Fmt.area(area) + "). Events start at the next check.");
        refreshZones(true);
        for (ZoneView z : zoneItems) if (z.id().equals(id)) zoneList.getSelectionModel().select(z);
    }

    private void runDemo() {
        ZoneView z = zoneList.getSelectionModel().getSelectedItem();
        if (z == null) return;
        var result = ctx.service.demoRouteVehiclesInto(z.id(), 10);
        if (result.vehicleIds().isEmpty()) {
            demoMessage.show("warn", result.message());
        } else {
            demoMessage.show("info", "Demo: routing vehicles " + result.vehicleIds() + " to \"" + z.name()
                    + "\" (" + result.roadNodesInZone() + " road nodes inside). Watch the events table as they arrive.");
        }
    }

    private void deleteSelected() {
        ZoneView z = zoneList.getSelectionModel().getSelectedItem();
        if (z == null || z.builtIn()) return;
        ctx.service.getGeofenceEngine().deleteZone(z.id());
        message.show("info", "Deleted \"" + z.name() + "\". Its past events remain in the log.");
        refreshZones(true);
    }

    private void refreshZones(boolean force) {
        List<ZoneView> zones = ctx.service.getZones();
        StringBuilder sig = new StringBuilder();
        for (ZoneView z : zones) sig.append(z.id()).append(':').append(z.occupancy()).append(';');
        if (force || !sig.toString().equals(zoneSignature)) {
            zoneSignature = sig.toString();
            String selectedId = zoneList.getSelectionModel().getSelectedItem() == null ? null
                    : zoneList.getSelectionModel().getSelectedItem().id();
            zoneItems.setAll(zones);
            if (selectedId != null) {
                for (ZoneView z : zoneItems) if (z.id().equals(selectedId)) zoneList.getSelectionModel().select(z);
            }
            String filter = zoneFilter.getValue();
            List<String> names = new ArrayList<>();
            names.add("All zones");
            for (ZoneView z : zones) names.add(z.name());
            if (!names.equals(zoneFilter.getItems())) {
                zoneFilter.getItems().setAll(names);
                zoneFilter.setValue(names.contains(filter) ? filter : "All zones");
            }
        }
        ctx.syncZonesToMap(force);
    }

    private void refreshEvents() {
        List<ZoneEvent> events = ctx.service.getEventHistory();
        String filter = zoneFilter.getValue();
        List<EventRow> rows = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && rows.size() < EVENT_ROWS; i--) {
            ZoneEvent ev = events.get(i);
            String zoneName = ctx.service.zoneDisplayName(ev.zoneId());
            if (filter != null && !"All zones".equals(filter) && !filter.equals(zoneName)) continue;
            rows.add(new EventRow(Fmt.clockMillis(ev.timestamp()), "Vehicle " + ev.vehicleId(), zoneName, ev.type().name()));
        }
        eventRows.setAll(rows);
        long enter = ctx.service.getEnterEventCount(), exit = ctx.service.getExitEventCount();
        eventSummary.setText(Fmt.count(enter + exit) + " total · " + Fmt.count(enter) + " enter · " + Fmt.count(exit)
                + " exit" + (rows.size() >= EVENT_ROWS ? " · showing newest " + EVENT_ROWS : ""));
    }

    /** UI tour: start drawing a new zone. */
    void beginDrawingForTour(String name) {
        nameField.setText(name);
        draft.clear();
        syncDraft(true);
        setDrawing(true);
    }

    /** UI tour: select a zone and press the demo button. */
    void runDemoForTour(int zoneIndex) {
        refreshZones(true);
        zoneList.getSelectionModel().select(zoneIndex);
        runDemo();
    }

    /** UI tour: press "Create zone". */
    void createZoneForTour() {
        createZone();
    }

    record EventRow(SimpleStringProperty time, SimpleStringProperty vehicle, SimpleStringProperty zone, SimpleStringProperty type) {
        EventRow(String time, String vehicle, String zone, String type) {
            this(new SimpleStringProperty(time), new SimpleStringProperty(vehicle), new SimpleStringProperty(zone), new SimpleStringProperty(type));
        }
    }

    private final class ZoneCell extends ListCell<ZoneView> {
        @Override
        protected void updateItem(ZoneView z, boolean empty) {
            super.updateItem(z, empty);
            if (empty || z == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            int index = zoneItems.indexOf(z);
            Region swatch = new Region();
            swatch.getStyleClass().add("zone-swatch");
            swatch.setStyle("-fx-background-color: " + AppContext.zoneColor(index) + ";");
            Label name = Ui.label(z.name());
            name.setStyle("-fx-font-weight: bold;");
            Label meta = Ui.label((z.builtIn() ? "Built-in" : "Custom") + " · " + Fmt.area(z.areaSqMeters())
                    + " · " + z.polygon().size() + " vertices", "muted");
            Label inside = Ui.pill(z.occupancy() + " inside", z.occupancy() > 0 ? "ok" : "neutral");
            VBox text = new VBox(2, name, meta);
            HBox row = new HBox(10, swatch, text, Ui.hSpacer(), inside);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
        }
    }
}
