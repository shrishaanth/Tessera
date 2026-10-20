package com.geotracker.fx;

import com.geotracker.fx.ui.Fmt;
import com.geotracker.fx.ui.Icons;
import com.geotracker.fx.ui.Ui;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Application frame: sidebar navigation on the left, the active page on the right. */
public final class AppShell extends BorderPane {

    private final AppContext ctx;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, ToggleButton> navButtons = new LinkedHashMap<>();
    private final ToggleGroup navGroup = new ToggleGroup();
    private final StackPane content = new StackPane();
    private final Ui.StateChip stateChip = new Ui.StateChip();
    private final Label statusFleet = Ui.label("", "status-line");
    private final Label statusRate = Ui.label("", "status-line");
    private Page current;

    public AppShell(AppContext ctx, List<Page> pageList) {
        this.ctx = ctx;
        getStyleClass().add("app-shell");

        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(214);
        sidebar.setMinWidth(214);

        StackPane mark = new StackPane(Icons.icon(Icons.LOGO, 20));
        mark.getStyleClass().add("brand-mark");
        VBox brandText = new VBox(0, Ui.label("Tessera", "brand-name"), Ui.label("Fleet tracking", "brand-tagline"));
        HBox brand = new HBox(mark, brandText);
        brand.getStyleClass().add("brand");
        brand.setAlignment(Pos.CENTER_LEFT);
        sidebar.getChildren().add(brand);

        int index = 1;
        for (Page page : pageList) {
            pages.put(page.title(), page);
            ToggleButton b = new ToggleButton(page.title());
            b.setGraphic(Icons.icon(page.icon(), 18));
            b.getStyleClass().setAll("nav-button");
            b.setToggleGroup(navGroup);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setTooltip(new Tooltip(page.title() + "  (⌘" + index + ")"));
            String title = page.title();
            b.setOnAction(e -> navigate(title));
            navButtons.put(page.title(), b);
            sidebar.getChildren().add(b);
            index++;
        }
        // Keep the current page selected even if its button is clicked again.
        navGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
            if (sel == null && old != null) old.setSelected(true);
        });

        statusFleet.setWrapText(true);
        statusRate.setWrapText(true);
        VBox status = new VBox(stateChip, statusFleet, statusRate);
        status.getStyleClass().add("sidebar-status");
        sidebar.getChildren().addAll(Ui.vSpacer(), status);

        content.getStyleClass().add("content");
        setLeft(sidebar);
        setCenter(content);

        ctx.setNavigator(this::navigate);
        ctx.onSimulationChanged(() -> stateChip.set(ctx.service.getSimulationState()));

        sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) return;
            int i = 1;
            for (String title : pages.keySet()) {
                KeyCombination combo = new KeyCodeCombination(KeyCode.valueOf("DIGIT" + i), KeyCombination.SHORTCUT_DOWN);
                scene.getAccelerators().put(combo, () -> navigate(title));
                i++;
            }
        });
    }

    public void navigate(String title) {
        Page next = pages.get(title);
        if (next == null) return;
        ToggleButton b = navButtons.get(title);
        if (b != null && !b.isSelected()) b.setSelected(true);
        if (next == current) return;
        if (current != null) current.onHide();
        current = next;
        content.getChildren().setAll(next.view());
        if (ctx.liveData() != null) ctx.liveData().setMapPayloadWanted(next.usesMap());
        next.onShow();
        LiveData.Frame last = ctx.lastFrame.get();
        if (last != null) deliver(last);
        if (ctx.liveData() != null) ctx.liveData().refreshNow();
    }

    public Page currentPage() {
        return current;
    }

    /** Receives every live frame on the FX thread. */
    public void onFrame(LiveData.Frame frame) {
        ctx.lastFrame.set(frame);
        stateChip.set(frame.state());
        int tracked = frame.vehicles().size();
        statusFleet.setText(Fmt.count(tracked) + " / " + Fmt.count(ctx.service.getTotalVehicleCount()) + " vehicles tracked");
        if (frame.metrics() != null) {
            statusRate.setText(Fmt.rate(frame.metrics().generatedRate()) + " updates/s · sim " + Fmt.duration(frame.metrics().simulatedSeconds()));
        }
        deliver(frame);
    }

    private void deliver(LiveData.Frame frame) {
        if (current == null) return;
        if (current.usesMap() && frame.vehiclesJson() != null) {
            ctx.map.setVehicles(frame.vehiclesJson());
        }
        current.onFrame(frame);
    }
}
