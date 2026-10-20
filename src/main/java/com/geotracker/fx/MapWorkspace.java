package com.geotracker.fx;

import com.geotracker.fx.ui.Ui;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;

/** Layout for tool pages: a scrolling control panel on the left and the shared map on the right. */
public class MapWorkspace extends BorderPane {

    protected final VBox panel = new VBox();
    protected final StackPane mapHost = new StackPane();
    private final HBox hint = new HBox();
    private final Label hintText = new Label();

    public MapWorkspace(String title, String subtitle) {
        getStyleClass().add("page-flush");

        panel.getStyleClass().add("side-panel-content");
        panel.getChildren().add(Ui.pageHeader(title, subtitle));
        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("side-panel-scroll");
        VBox side = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        side.getStyleClass().add("side-panel");
        side.setPrefWidth(372);
        side.setMinWidth(320);
        side.setMaxWidth(420);
        setLeft(side);

        mapHost.setMinSize(0, 0);
        hint.getStyleClass().add("map-hint");
        hint.getChildren().add(hintText);
        hint.setAlignment(Pos.CENTER);
        hint.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        hint.setMouseTransparent(true);
        hint.setVisible(false);
        StackPane.setAlignment(hint, Pos.TOP_CENTER);
        StackPane.setMargin(hint, new Insets(14, 0, 0, 0));
        mapHost.getChildren().add(hint);
        setCenter(mapHost);
    }

    protected void addToPanel(Node... nodes) {
        panel.getChildren().addAll(nodes);
    }

    /** Instruction banner over the map (e.g. while picking a point). */
    protected void showHint(String text) {
        hintText.setText(text);
        hint.setVisible(true);
    }

    protected void hideHint() {
        hint.setVisible(false);
    }

    protected static Region divider() {
        Region r = new Region();
        r.getStyleClass().add("section-divider");
        return r;
    }

    protected static Label section(String text) {
        return Ui.label(text, "section-title");
    }
}
