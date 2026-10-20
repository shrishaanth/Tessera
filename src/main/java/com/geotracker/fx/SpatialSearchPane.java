package com.geotracker.fx;

import com.geotracker.fx.ui.Fmt;
import com.geotracker.fx.ui.Icons;
import com.geotracker.fx.ui.Ui;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.routing.GeoUtils;
import com.geotracker.service.ApplicationService.SpatialQueryResult;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

/** Radius and bounding-box queries against the live spatial index, verified by brute force. */
public class SpatialSearchPane extends MapWorkspace implements Page {

    private enum Mode { RADIUS, BOX }

    private final AppContext ctx;
    private final Ui.Segmented<Mode> mode = new Ui.Segmented<>(List.of(Mode.RADIUS, Mode.BOX), List.of("Radius", "Bounding box"), Mode.RADIUS);
    private final TextField centerLat = field("47.648200");
    private final TextField centerLng = field("-122.333200");
    private final TextField radius = field("150");
    private final TextField south = field("47.6470");
    private final TextField west = field("-122.3345");
    private final TextField north = field("47.6495");
    private final TextField east = field("-122.3315");
    private final ToggleButton pickCenter = new ToggleButton("Pick center");
    private final ToggleButton drawBox = new ToggleButton("Draw box");
    private final CheckBox live = new CheckBox("Re-run every second while visible");
    private final Ui.Message message = new Ui.Message();
    private final VBox radiusForm, boxForm;
    private double[] boxCorner = null;

    private final VBox resultBox = new VBox(10);
    private final Label resultCount = Ui.label("–", "stat-value");
    private final Label resultCaption = Ui.label("", "stat-caption");
    private final GridPane resultGrid = Ui.keyValueGrid();
    private final Label indexTime, bruteTime, candidates, verified;
    private final ObservableList<ResultRow> rows = FXCollections.observableArrayList();
    private final TableView<ResultRow> table = new TableView<>(rows);
    private boolean hasQuery = false;
    private long lastLiveRun = 0;

    public SpatialSearchPane(AppContext ctx) {
        super("Spatial Search", "Query the live copy-on-write quadtree");
        this.ctx = ctx;

        HBox centerRow = new HBox(6, labelled("Latitude", centerLat), labelled("Longitude", centerLng));
        pickCenter.setGraphic(Icons.icon(Icons.PLACE, 16));
        pickCenter.setOnAction(e -> updatePickMode());
        HBox radiusRow = new HBox(6, labelled("Radius (m)", radius), new VBox(Ui.vSpacer(), pickCenter));
        radiusForm = new VBox(10, centerRow, radiusRow);

        HBox sw = new HBox(6, labelled("South (min lat)", south), labelled("West (min lng)", west));
        HBox ne = new HBox(6, labelled("North (max lat)", north), labelled("East (max lng)", east));
        drawBox.setGraphic(Icons.icon(Icons.BOX, 16));
        drawBox.setOnAction(e -> {
            boxCorner = null;
            updatePickMode();
        });
        Button useView = Ui.button("Use map view", Icons.VIEW);
        useView.setOnAction(e -> useCurrentView());
        boxForm = new VBox(10, sw, ne, new HBox(8, drawBox, useView));

        mode.setOnChange(m -> {
            pickCenter.setSelected(false);
            drawBox.setSelected(false);
            updatePickMode();
            showForm();
        });

        Button run = Ui.button("Run query", Icons.SEARCH, "primary");
        run.setDefaultButton(true);
        run.setOnAction(e -> runQuery(true));
        Button clear = Ui.button("Clear", Icons.CLOSE);
        clear.setOnAction(e -> clearQuery());
        HBox actions = new HBox(8, run, clear);

        VBox queryBox = new VBox(12, mode, radiusForm, boxForm, actions, live, message);
        showForm();

        indexTime = Ui.addRow(resultGrid, "Index query");
        bruteTime = Ui.addRow(resultGrid, "Brute-force scan");
        candidates = Ui.addRow(resultGrid, "Candidates");
        verified = Ui.addRow(resultGrid, "Verification");
        verified.setWrapText(true);

        TableColumn<ResultRow, Number> idCol = new TableColumn<>("Vehicle");
        idCol.setCellValueFactory(c -> c.getValue().id);
        idCol.setPrefWidth(70);
        TableColumn<ResultRow, Number> distCol = new TableColumn<>("Distance");
        distCol.setPrefWidth(84);
        distCol.setCellValueFactory(c -> c.getValue().distance);
        distCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Number v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : Fmt.decimal(v.doubleValue(), 1) + " m");
            }
        });
        TableColumn<ResultRow, String> posCol = new TableColumn<>("Position");
        posCol.setCellValueFactory(c -> c.getValue().position);
        table.getColumns().addAll(List.of(idCol, distCol, posCol));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(260);
        table.setPlaceholder(Ui.label("No vehicles in the query area", "muted"));
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, r) -> {
            if (r != null) {
                ctx.selectedVehicle.set(r.id.get());
                ctx.map.selectVehicle(r.id.get(), false);
            }
        });

        resultBox.getChildren().addAll(section("Result"), new VBox(2, resultCount, resultCaption), resultGrid, table);
        resultBox.setVisible(false);
        resultBox.setManaged(false);

        addToPanel(queryBox, divider(), resultBox,
                Ui.label("Both the index query and the brute-force scan read the same published index snapshots, "
                        + "so their results must agree exactly even while vehicles move.", "note"));
    }

    private static TextField field(String value) {
        TextField f = new TextField(value);
        f.setPrefColumnCount(8);
        return f;
    }

    private static VBox labelled(String label, TextField field) {
        VBox box = new VBox(4, Ui.label(label, "field-label"), field);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private void showForm() {
        boolean r = mode.selected() == Mode.RADIUS;
        radiusForm.setVisible(r);
        radiusForm.setManaged(r);
        boxForm.setVisible(!r);
        boxForm.setManaged(!r);
    }

    @Override public String title() { return "Spatial Search"; }
    @Override public String icon() { return Icons.SEARCH; }
    @Override public Node view() { return this; }
    @Override public boolean usesMap() { return true; }

    @Override
    public void onShow() {
        ctx.map.attachTo(mapHost);
        ctx.map.setLayerVisible("vehicles", true);
        ctx.map.setLayerVisible("zones", false);
        ctx.map.setLayerVisible("route", false);
        ctx.map.setLayerVisible("roadGraph", false);
        ctx.map.setLayerVisible("draft", false);
        ctx.map.setLayerVisible("search", true);
        ctx.map.setOnPick(this::onPick);
        ctx.map.setOnEscape(() -> {
            pickCenter.setSelected(false);
            drawBox.setSelected(false);
            updatePickMode();
        });
        updatePickMode();
        if (!hasQuery) drawArea(false);
    }

    @Override
    public void onHide() {
        pickCenter.setSelected(false);
        drawBox.setSelected(false);
        ctx.map.setPickMode(null);
        hideHint();
    }

    @Override
    public void onFrame(LiveData.Frame frame) {
        if (live.isSelected() && hasQuery && frame.timeMillis() - lastLiveRun >= 950) {
            lastLiveRun = frame.timeMillis();
            runQuery(false);
        }
    }

    private void updatePickMode() {
        if (pickCenter.isSelected() && mode.selected() == Mode.RADIUS) {
            ctx.map.setPickMode("center");
            showHint("Click the map to set the search centre  ·  Esc to cancel");
        } else if (drawBox.isSelected() && mode.selected() == Mode.BOX) {
            ctx.map.setPickMode("box");
            showHint(boxCorner == null ? "Click the first corner of the box  ·  Esc to cancel"
                    : "Click the opposite corner  ·  Esc to cancel");
        } else {
            ctx.map.setPickMode(null);
            hideHint();
        }
    }

    private void onPick(String m, double[] latLng) {
        if ("center".equals(m)) {
            centerLat.setText(Fmt.coord(latLng[0]));
            centerLng.setText(Fmt.coord(latLng[1]));
            pickCenter.setSelected(false);
            updatePickMode();
            runQuery(true);
        } else if ("box".equals(m)) {
            if (boxCorner == null) {
                boxCorner = latLng;
                ctx.map.setSearchArea("{\"type\":\"point\",\"center\":" + MapPayloads.latLng(new StringBuilder(), latLng[0], latLng[1]) + "}");
                updatePickMode();
                return;
            }
            south.setText(Fmt.coord(Math.min(boxCorner[0], latLng[0])));
            north.setText(Fmt.coord(Math.max(boxCorner[0], latLng[0])));
            west.setText(Fmt.coord(Math.min(boxCorner[1], latLng[1])));
            east.setText(Fmt.coord(Math.max(boxCorner[1], latLng[1])));
            boxCorner = null;
            drawBox.setSelected(false);
            updatePickMode();
            runQuery(true);
        }
    }

    private void useCurrentView() {
        double[] b = ctx.map.getViewBounds();
        if (b == null) return;
        south.setText(Fmt.coord(b[0]));
        west.setText(Fmt.coord(b[1]));
        north.setText(Fmt.coord(b[2]));
        east.setText(Fmt.coord(b[3]));
        runQuery(false);
    }

    private Double num(TextField f) {
        try {
            f.getStyleClass().remove("invalid");
            return Double.parseDouble(f.getText().trim());
        } catch (NumberFormatException e) {
            f.getStyleClass().add("invalid");
            return null;
        }
    }

    /** Draws the query area from the current inputs. Returns false if inputs are invalid. */
    private boolean drawArea(boolean fit) {
        if (mode.selected() == Mode.RADIUS) {
            Double lat = num(centerLat), lng = num(centerLng), r = num(radius);
            if (lat == null || lng == null || r == null) return false;
            StringBuilder sb = new StringBuilder("{\"type\":\"circle\",\"center\":");
            MapPayloads.latLng(sb, lat, lng).append(",\"radius\":").append(r).append(",\"fit\":").append(fit).append('}');
            ctx.map.setSearchArea(sb.toString());
        } else {
            Double s = num(south), w = num(west), n = num(north), e = num(east);
            if (s == null || w == null || n == null || e == null) return false;
            ctx.map.setSearchArea("{\"type\":\"box\",\"bounds\":[" + s + "," + w + "," + n + "," + e + "],\"fit\":" + fit + "}");
        }
        return true;
    }

    private void runQuery(boolean fit) {
        message.clear();
        SpatialQueryResult result;
        double cLat = 0, cLng = 0;
        if (mode.selected() == Mode.RADIUS) {
            Double lat = num(centerLat), lng = num(centerLng), r = num(radius);
            if (lat == null || lng == null || r == null) {
                message.show("bad", "Centre and radius must be numbers.");
                return;
            }
            if (Math.abs(lat) > 90 || Math.abs(lng) > 180) {
                message.show("bad", "Latitude must be within ±90 and longitude within ±180.");
                return;
            }
            if (r <= 0 || r > 20_000) {
                radius.getStyleClass().add("invalid");
                message.show("bad", "Radius must be between 1 m and 20 km.");
                return;
            }
            cLat = lat;
            cLng = lng;
            result = ctx.service.radiusQueryVerified(lng, lat, r);
        } else {
            Double s = num(south), w = num(west), n = num(north), e = num(east);
            if (s == null || w == null || n == null || e == null) {
                message.show("bad", "All four box edges must be numbers.");
                return;
            }
            if (s >= n || w >= e) {
                message.show("bad", "South must be less than north and west less than east.");
                return;
            }
            cLat = (s + n) / 2;
            cLng = (w + e) / 2;
            result = ctx.service.rangeQueryVerified(new BoundingBox(w, s, e, n));
        }
        hasQuery = true;
        drawArea(fit);
        show(result, cLat, cLng);
    }

    private void show(SpatialQueryResult r, double cLat, double cLng) {
        int n = r.vehicleIds().size();
        resultCount.setText(Fmt.count(n) + (n == 1 ? " vehicle" : " vehicles"));
        resultCaption.setText("of " + Fmt.count(ctx.service.getActiveVehicleCount()) + " tracked"
                + ("radius".equals(r.kind()) ? " · within the circle" : " · inside the box"));
        indexTime.setText(Fmt.nanosAsMillis(r.indexNanos()) + "  (quadtree, 4 shards)");
        bruteTime.setText(Fmt.nanosAsMillis(r.bruteForceNanos()) + "  (checks every vehicle)");
        candidates.setText("radius".equals(r.kind())
                ? Fmt.count(r.candidates()) + " in bounding box → " + Fmt.count(n) + " within radius"
                : Fmt.count(r.candidates()) + " returned by the index");
        if (r.matchesBruteForce()) {
            verified.setText("✓ Matches brute-force scan (" + Fmt.count(r.bruteForceCount()) + " = " + Fmt.count(n) + ")");
            verified.setStyle("-fx-text-fill: #137333;");
        } else {
            verified.setText("Mismatch: index " + Fmt.count(n) + " vs brute force " + Fmt.count(r.bruteForceCount()));
            verified.setStyle("-fx-text-fill: #c5221f;");
        }
        if (n == 0 && ctx.service.getActiveVehicleCount() == 0) {
            message.show("info", "No vehicles are tracked yet. Start the simulation on the Live Map, then run the query again.");
        }

        List<ResultRow> list = new ArrayList<>(Math.min(n, 2000));
        for (long id : r.vehicleIds()) {
            if (list.size() >= 2000) break;
            Position p = ctx.service.getVehiclePosition(id);
            if (p == null) continue;
            double d = GeoUtils.haversineMeters(cLat, cLng, p.y(), p.x());
            list.add(new ResultRow(id, d, String.format(java.util.Locale.US, "%.5f, %.5f", p.y(), p.x())));
        }
        list.sort((a, b) -> Double.compare(a.distance.get(), b.distance.get()));
        rows.setAll(list);
        ctx.map.setSearchResults(MapPayloads.ids(r.vehicleIds()));
        resultBox.setVisible(true);
        resultBox.setManaged(true);
    }

    private void clearQuery() {
        hasQuery = false;
        rows.clear();
        resultBox.setVisible(false);
        resultBox.setManaged(false);
        message.clear();
        ctx.map.setSearchArea(null);
        ctx.map.setSearchResults("[]");
    }

    /** Programmatic entry point (used by the UI tour). */
    public void runQueryForTour() {
        runQuery(true);
    }

    record ResultRow(SimpleLongProperty id, SimpleDoubleProperty distance, SimpleStringProperty position) {
        ResultRow(long id, double distance, String position) {
            this(new SimpleLongProperty(id), new SimpleDoubleProperty(distance), new SimpleStringProperty(position));
        }
    }
}
