package com.geotracker.fx.ui;

import com.geotracker.service.ApplicationService.SimulationState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;

import java.util.List;
import java.util.function.Consumer;

/** Small factory methods for the recurring building blocks of the UI. */
public final class Ui {
    private Ui() {}

    public static Label label(String text, String... styleClasses) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styleClasses);
        return l;
    }

    /** Page title + subtitle on the left, optional actions on the right. */
    public static HBox pageHeader(String title, String subtitle, Node... actions) {
        VBox text = new VBox(2, label(title, "page-title"), label(subtitle, "page-subtitle"));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(12, text);
        box.getChildren().addAll(actions);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("page-header");
        return box;
    }

    /** White card with an optional title row. */
    public static VBox card(String title, Node... content) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        if (title != null) card.getChildren().add(label(title, "card-title"));
        card.getChildren().addAll(content);
        return card;
    }

    public static VBox card(String title, String subtitle, Node... content) {
        VBox card = card(null);
        VBox head = new VBox(2, label(title, "card-title"), label(subtitle, "card-subtitle"));
        card.getChildren().add(head);
        card.getChildren().addAll(content);
        return card;
    }

    public static Button button(String text, String icon, String... styleClasses) {
        Button b = new Button(text);
        if (icon != null) b.setGraphic(Icons.icon(icon, 16));
        b.getStyleClass().addAll(styleClasses);
        return b;
    }

    public static Button iconButton(String icon, String tooltip, String... styleClasses) {
        Button b = new Button();
        b.setGraphic(Icons.icon(icon, 18));
        b.getStyleClass().add("icon-button");
        b.getStyleClass().addAll(styleClasses);
        b.setTooltip(new Tooltip(tooltip));
        b.setAccessibleText(tooltip);
        return b;
    }

    public static Region hSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Region vSpacer() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Separator vSeparator() {
        Separator s = new Separator(javafx.geometry.Orientation.VERTICAL);
        s.getStyleClass().add("toolbar-separator");
        return s;
    }

    /** Label/value row for detail panels. */
    public static GridPane keyValueGrid() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("kv-grid");
        grid.setHgap(16);
        grid.setVgap(7);
        ColumnConstraints k = new ColumnConstraints();
        k.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints v = new ColumnConstraints();
        v.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(k, v);
        return grid;
    }

    public static Label addRow(GridPane grid, String key) {
        int row = grid.getRowCount();
        Label value = label("–", "kv-value");
        value.setWrapText(true);
        grid.addRow(row, label(key, "kv-key"), value);
        return value;
    }

    /** Coloured dot + text chip describing the simulation state. */
    public static final class StateChip extends HBox {
        private final Circle dot = new Circle(4);
        private final Label text = new Label();

        public StateChip() {
            super(6);
            setAlignment(Pos.CENTER_LEFT);
            getStyleClass().add("state-chip");
            dot.getStyleClass().add("state-dot");
            text.getStyleClass().add("state-text");
            getChildren().addAll(dot, text);
            setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
            set(SimulationState.STOPPED);
        }

        public void set(SimulationState state) {
            getStyleClass().removeAll("running", "paused", "stopped");
            switch (state) {
                case RUNNING -> { getStyleClass().add("running"); text.setText("Live"); }
                case PAUSED -> { getStyleClass().add("paused"); text.setText("Paused"); }
                case STOPPED -> { getStyleClass().add("stopped"); text.setText("Stopped"); }
            }
        }
    }

    /** Small rounded label with a semantic colour (style classes: ok, warn, bad, info, neutral). */
    public static Label pill(String text, String kind) {
        Label l = new Label(text);
        l.getStyleClass().addAll("pill", kind);
        return l;
    }

    /** Segmented control built from toggle buttons; exactly one option is always selected. */
    public static final class Segmented<T> extends HBox {
        private final ToggleGroup group = new ToggleGroup();
        private final List<T> values;
        private Consumer<T> onChange = v -> {};
        private boolean updating = false;

        public Segmented(List<T> values, List<String> labels, T initial) {
            this.values = values;
            getStyleClass().add("segmented");
            setAlignment(Pos.CENTER_LEFT);
            for (int i = 0; i < values.size(); i++) {
                ToggleButton b = new ToggleButton(labels.get(i));
                b.setUserData(values.get(i));
                b.setToggleGroup(group);
                b.getStyleClass().add("segment");
                if (i == 0) b.getStyleClass().add("first");
                if (i == values.size() - 1) b.getStyleClass().add("last");
                b.setFocusTraversable(true);
                getChildren().add(b);
                if (values.get(i).equals(initial)) b.setSelected(true);
            }
            group.selectedToggleProperty().addListener((obs, old, sel) -> {
                if (sel == null) {
                    // Keep one option selected: re-select the previous one.
                    if (old != null) old.setSelected(true);
                    return;
                }
                if (!updating) {
                    @SuppressWarnings("unchecked") T v = (T) sel.getUserData();
                    onChange.accept(v);
                }
            });
        }

        public void setOnChange(Consumer<T> handler) {
            this.onChange = handler;
        }

        public void select(T value) {
            updating = true;
            try {
                for (Toggle t : group.getToggles()) {
                    if (t.getUserData().equals(value)) t.setSelected(true);
                }
            } finally {
                updating = false;
            }
        }

        @SuppressWarnings("unchecked")
        public T selected() {
            Toggle t = group.getSelectedToggle();
            return t == null ? values.get(0) : (T) t.getUserData();
        }
    }

    /** Big-number tile: value, label and a secondary caption. */
    public static final class StatTile extends VBox {
        private final Label value = label("–", "stat-value");
        private final Label caption = label("", "stat-caption");

        public StatTile(String title, String icon) {
            super(6);
            getStyleClass().addAll("card", "stat-tile");
            Label t = label(title, "stat-title");
            HBox head = new HBox(8);
            head.setAlignment(Pos.CENTER_LEFT);
            if (icon != null) {
                StackPane badge = new StackPane(Icons.icon(icon, 16));
                badge.getStyleClass().add("stat-icon");
                head.getChildren().add(badge);
            }
            head.getChildren().add(t);
            caption.setWrapText(true);
            getChildren().addAll(head, value, caption);
            setMinWidth(170);
            setPadding(new Insets(14, 16, 14, 16));
        }

        public void set(String v, String c) {
            value.setText(v);
            caption.setText(c);
        }

        public Label valueLabel() {
            return value;
        }
    }

    /** Wraps content in a vertical scroll pane that fits the width. */
    public static ScrollPane scroll(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.getStyleClass().add("page-scroll");
        return sp;
    }

    /** Inline validation / status message (style: info, ok, warn, bad). */
    public static final class Message extends HBox {
        private final Label text = new Label();

        public Message() {
            super(8);
            getStyleClass().add("message");
            text.setWrapText(true);
            text.getStyleClass().add("message-text");
            getChildren().add(text);
            setAlignment(Pos.TOP_LEFT);
            clear();
        }

        public void show(String kind, String message) {
            getStyleClass().removeAll("info", "ok", "warn", "bad");
            getStyleClass().add(kind);
            text.setText(message);
            setVisible(true);
            setManaged(true);
        }

        public void clear() {
            setVisible(false);
            setManaged(false);
        }
    }
}
