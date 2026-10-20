package com.geotracker.fx;

import com.geotracker.fx.ui.Fmt;
import com.geotracker.fx.ui.Icons;
import com.geotracker.fx.ui.Ui;
import com.geotracker.routing.GeoUtils;
import com.geotracker.service.ApplicationService.VehicleInfo;
import javafx.beans.property.*;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Sortable, filterable table of every tracked vehicle, with a details panel. */
public class VehicleExplorerPane extends BorderPane implements Page {

    private static final List<String> STATUSES = List.of("All statuses", "Active", "Stale", "Paused", "Idle");
    private static final List<String> SOURCES = List.of("All sources", "Road graph", "CSV replay");

    private final AppContext ctx;
    private final ObservableList<VehicleRow> master = FXCollections.observableArrayList();
    private final Map<Long, VehicleRow> rowsById = new HashMap<>();
    private final FilteredList<VehicleRow> filtered = new FilteredList<>(master, r -> true);
    private final TableView<VehicleRow> table = new TableView<>();
    private final TextField search = new TextField();
    private final ChoiceBox<String> statusFilter = new ChoiceBox<>(FXCollections.observableArrayList(STATUSES));
    private final ChoiceBox<String> sourceFilter = new ChoiceBox<>(FXCollections.observableArrayList(SOURCES));
    private final Label countLabel = Ui.label("", "muted");
    private boolean syncingSelection = false;

    private final Label dTitle = Ui.label("No vehicle selected", "card-title");
    private final Label dHint = Ui.label("Select a row, or click a vehicle on the map.", "note");
    private final GridPane dGrid = Ui.keyValueGrid();
    private final Label dSource, dStatus, dSpeed, dHeading, dLat, dLng, dUpdated, dAge;
    private final Button showOnMap = Ui.button("Show on live map", Icons.MAP, "primary");

    public VehicleExplorerPane(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("page");

        search.setPromptText("Search vehicle ID");
        search.setPrefWidth(200);
        statusFilter.getSelectionModel().select(0);
        sourceFilter.getSelectionModel().select(0);
        search.textProperty().addListener((obs, o, n) -> applyFilter());
        statusFilter.valueProperty().addListener((obs, o, n) -> applyFilter());
        sourceFilter.valueProperty().addListener((obs, o, n) -> applyFilter());

        HBox filters = new HBox(10, search, statusFilter, sourceFilter, Ui.hSpacer(), countLabel);
        filters.setAlignment(Pos.CENTER_LEFT);

        buildTable();
        SortedList<VehicleRow> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.setPlaceholder(Ui.label("No vehicles to show. Start the simulation on the Live Map.", "muted"));
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox left = new VBox(12, Ui.pageHeader("Vehicles", "Every tracked vehicle with its latest telemetry"), filters, table);
        HBox.setHgrow(left, Priority.ALWAYS);

        dSource = Ui.addRow(dGrid, "Source");
        dStatus = Ui.addRow(dGrid, "Status");
        dSpeed = Ui.addRow(dGrid, "Speed");
        dHeading = Ui.addRow(dGrid, "Heading");
        dLat = Ui.addRow(dGrid, "Latitude");
        dLng = Ui.addRow(dGrid, "Longitude");
        dUpdated = Ui.addRow(dGrid, "Last update");
        dAge = Ui.addRow(dGrid, "Update age");
        dLat.getStyleClass().add("mono");
        dLng.getStyleClass().add("mono");
        showOnMap.setMaxWidth(Double.MAX_VALUE);
        showOnMap.setOnAction(e -> {
            long id = ctx.selectedVehicle.get();
            if (id < 0) return;
            ctx.navigate("Live Map");
            ctx.map.selectVehicle(id, true);
        });
        Label speedNote = Ui.label("Speed and heading are derived from successive positions in simulated time.", "note");
        VBox details = Ui.card(null, dTitle, dHint, dGrid, showOnMap, speedNote);
        details.setPrefWidth(300);
        details.setMinWidth(280);
        details.setMaxHeight(Region.USE_PREF_SIZE);

        HBox body = new HBox(18, left, details);
        setCenter(body);

        table.getSelectionModel().selectedItemProperty().addListener((obs, o, row) -> {
            if (syncingSelection) return;
            if (row != null) ctx.selectedVehicle.set(row.id.get());
        });
        ctx.selectedVehicle.addListener((obs, o, n) -> {
            syncTableSelection();
            refreshDetails(ctx.lastFrame.get());
        });
        refreshDetails(null);
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(numberColumn("ID", r -> r.id, 70, v -> String.valueOf(v.longValue())));
        TableColumn<VehicleRow, String> source = new TableColumn<>("Source");
        source.setCellValueFactory(c -> c.getValue().source);
        source.setPrefWidth(110);
        source.setCellFactory(col -> new PillCell(s -> "CSV replay".equals(s) ? "orange" : "info"));
        TableColumn<VehicleRow, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> c.getValue().status);
        status.setPrefWidth(90);
        status.setCellFactory(col -> new PillCell(s -> switch (s) {
            case "Active" -> "ok";
            case "Stale" -> "warn";
            default -> "neutral";
        }));
        table.getColumns().addAll(source, status);
        table.getColumns().add(numberColumn("Speed (km/h)", r -> r.speed, 110, v -> Fmt.decimal(v.doubleValue(), 1)));
        table.getColumns().add(numberColumn("Heading", r -> r.heading, 100,
                v -> v.doubleValue() < 0 ? "–" : Fmt.decimal(v.doubleValue(), 0) + "° " + GeoUtils.cardinal(v.doubleValue())));
        table.getColumns().add(numberColumn("Latitude", r -> r.lat, 120, v -> Fmt.coord(v.doubleValue())));
        table.getColumns().add(numberColumn("Longitude", r -> r.lng, 120, v -> Fmt.coord(v.doubleValue())));
        table.getColumns().add(numberColumn("Last update", r -> r.updated, 120, v -> Fmt.clockMillis(v.longValue())));
        table.getColumns().add(numberColumn("Age", r -> r.age, 80, v -> Fmt.age(v.longValue())));
        table.getSortOrder().add(table.getColumns().get(0));
    }

    private TableColumn<VehicleRow, Number> numberColumn(String name, Function<VehicleRow, ObservableValue<Number>> prop,
                                                         double width, Function<Number, String> format) {
        TableColumn<VehicleRow, Number> col = new TableColumn<>(name);
        col.setCellValueFactory(c -> prop.apply(c.getValue()));
        col.setPrefWidth(width);
        col.setCellFactory(c -> new TableCell<>() {
            {
                getStyleClass().add("numeric");
            }
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : format.apply(item));
            }
        });
        return col;
    }

    @Override public String title() { return "Vehicles"; }
    @Override public String icon() { return Icons.CAR; }
    @Override public Node view() { return this; }

    @Override
    public void onShow() {
        LiveData.Frame f = ctx.lastFrame.get();
        if (f != null) onFrame(f);
    }

    @Override
    public void onFrame(LiveData.Frame frame) {
        boolean membershipChanged = false;
        boolean statusChanged = false;
        Set<Long> seen = new HashSet<>(frame.vehicles().size() * 2);
        List<VehicleRow> added = new ArrayList<>();
        for (VehicleInfo v : frame.vehicles()) {
            seen.add(v.id());
            VehicleRow row = rowsById.get(v.id());
            if (row == null) {
                row = new VehicleRow(v.id());
                rowsById.put(v.id(), row);
                added.add(row);
            }
            statusChanged |= row.update(v, frame.timeMillis());
        }
        if (!added.isEmpty()) {
            master.addAll(added);
            membershipChanged = true;
        }
        if (rowsById.size() != seen.size()) {
            rowsById.keySet().removeIf(id -> !seen.contains(id));
            master.removeIf(r -> !seen.contains(r.id.get()));
            membershipChanged = true;
        }
        boolean statusFiltered = statusFilter.getSelectionModel().getSelectedIndex() > 0;
        if (membershipChanged || (statusChanged && statusFiltered)) applyFilter();
        if (!table.getSortOrder().isEmpty()) table.sort();
        updateCount();
        refreshDetails(frame);
    }

    private void applyFilter() {
        String text = search.getText() == null ? "" : search.getText().trim();
        int statusIdx = statusFilter.getSelectionModel().getSelectedIndex();
        int sourceIdx = sourceFilter.getSelectionModel().getSelectedIndex();
        String status = statusIdx > 0 ? STATUSES.get(statusIdx) : null;
        String source = sourceIdx > 0 ? SOURCES.get(sourceIdx) : null;
        Predicate<VehicleRow> p = r ->
                (text.isEmpty() || String.valueOf(r.id.get()).contains(text))
                && (status == null || status.equals(r.status.get()))
                && (source == null || source.equals(r.source.get()));
        filtered.setPredicate(p);
        updateCount();
        syncTableSelection();
    }

    private void updateCount() {
        countLabel.setText("Showing " + Fmt.count(filtered.size()) + " of " + Fmt.count(master.size()) + " tracked vehicles");
    }

    private void syncTableSelection() {
        long id = ctx.selectedVehicle.get();
        VehicleRow row = rowsById.get(id);
        syncingSelection = true;
        try {
            if (row == null || !table.getItems().contains(row)) {
                table.getSelectionModel().clearSelection();
            } else if (table.getSelectionModel().getSelectedItem() != row) {
                table.getSelectionModel().select(row);
                table.scrollTo(row);
            }
        } finally {
            syncingSelection = false;
        }
    }

    private void refreshDetails(LiveData.Frame frame) {
        long id = ctx.selectedVehicle.get();
        VehicleInfo v = (frame == null || id < 0) ? null : AppContext.find(frame.vehicles(), id);
        boolean has = v != null;
        dGrid.setVisible(has);
        dGrid.setManaged(has);
        dHint.setVisible(!has);
        dHint.setManaged(!has);
        showOnMap.setDisable(!has);
        if (!has) {
            dTitle.setText(id < 0 ? "No vehicle selected" : "Vehicle " + id + " (not tracked)");
            return;
        }
        dTitle.setText("Vehicle " + v.id());
        dSource.setText(AppContext.sourceLabel(v.source()));
        dStatus.setText(v.status());
        dSpeed.setText(Fmt.decimal(v.speedKmh(), 1) + " km/h");
        dHeading.setText(v.headingDeg() < 0 ? "Unknown (no movement yet)" : Fmt.decimal(v.headingDeg(), 0) + "° " + GeoUtils.cardinal(v.headingDeg()));
        dLat.setText(Fmt.coord(v.y()));
        dLng.setText(Fmt.coord(v.x()));
        dUpdated.setText(Fmt.clockMillis(v.lastUpdate()));
        dAge.setText(Fmt.age(frame.timeMillis() - v.lastUpdate()));
    }

    /** Table row with observable properties so cells update in place. */
    static final class VehicleRow {
        final ReadOnlyLongWrapper id;
        final SimpleStringProperty source = new SimpleStringProperty();
        final SimpleStringProperty status = new SimpleStringProperty();
        final SimpleDoubleProperty speed = new SimpleDoubleProperty();
        final SimpleDoubleProperty heading = new SimpleDoubleProperty();
        final SimpleDoubleProperty lat = new SimpleDoubleProperty();
        final SimpleDoubleProperty lng = new SimpleDoubleProperty();
        final SimpleLongProperty updated = new SimpleLongProperty();
        final SimpleLongProperty age = new SimpleLongProperty();

        VehicleRow(long id) {
            this.id = new ReadOnlyLongWrapper(id);
        }

        /** Returns true if the status changed. */
        boolean update(VehicleInfo v, long now) {
            boolean statusChanged = !v.status().equals(status.get());
            source.set(AppContext.sourceLabel(v.source()));
            status.set(v.status());
            speed.set(v.speedKmh());
            heading.set(v.headingDeg());
            lat.set(v.y());
            lng.set(v.x());
            updated.set(v.lastUpdate());
            age.set(Math.max(0, now - v.lastUpdate()));
            return statusChanged;
        }
    }

    /** Text cell rendered as a coloured pill. */
    private static final class PillCell extends TableCell<VehicleRow, String> {
        private final Label pill = new Label();
        private final Function<String, String> kind;

        PillCell(Function<String, String> kind) {
            this.kind = kind;
            pill.getStyleClass().add("pill");
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            pill.setText(item);
            pill.getStyleClass().setAll("pill", kind.apply(item));
            setGraphic(pill);
            setText(null);
        }
    }
}
