package com.geotracker.fx;

import com.geotracker.fx.ui.Fmt;
import com.geotracker.fx.ui.Icons;
import com.geotracker.fx.ui.Ui;
import com.geotracker.service.ApplicationService;
import com.geotracker.service.ApplicationService.SimulationState;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;

import java.util.Arrays;
import java.util.List;

/** Toolbar with the simulation lifecycle buttons, fleet size and playback speed. */
public class SimulationControlPane extends FlowPane {

    private static final List<Double> SPEEDS = List.of(0.5, 1.0, 2.0, 5.0, 10.0);

    private final AppContext ctx;
    private final ApplicationService service;
    private final Ui.StateChip chip = new Ui.StateChip();
    private final Button startBtn = Ui.button("Start", Icons.PLAY, "primary");
    private final Button pauseBtn = Ui.button("Pause", Icons.PAUSE);
    private final Button resumeBtn = Ui.button("Resume", Icons.PLAY);
    private final Button stopBtn = Ui.button("Stop", Icons.STOP, "danger");
    private final Button resetBtn = Ui.button("Reset", Icons.RESET);
    private final Ui.Segmented<Integer> fleet;
    private final Ui.Segmented<Double> speed;
    private final Tooltip fleetTip = new Tooltip();

    public SimulationControlPane(AppContext ctx) {
        super(18, 8);
        this.ctx = ctx;
        this.service = ctx.service;
        getStyleClass().add("map-toolbar");
        setAlignment(Pos.CENTER_LEFT);

        startBtn.setTooltip(new Tooltip("Start a new run from t = 0"));
        pauseBtn.setTooltip(new Tooltip("Freeze vehicles in place"));
        resumeBtn.setTooltip(new Tooltip("Continue the paused run"));
        stopBtn.setTooltip(new Tooltip("End the run; vehicles stay at their last positions"));
        resetBtn.setTooltip(new Tooltip("Stop and clear positions, counters and geofence events"));

        startBtn.setOnAction(e -> act(service::startSimulation));
        pauseBtn.setOnAction(e -> act(service::pauseSimulation));
        resumeBtn.setOnAction(e -> act(service::resumeSimulation));
        stopBtn.setOnAction(e -> act(service::stopSimulation));
        resetBtn.setOnAction(e -> act(service::resetSimulation));

        List<Integer> counts = Arrays.stream(ApplicationService.SUPPORTED_VEHICLE_COUNTS).boxed().toList();
        fleet = new Ui.Segmented<>(counts, counts.stream().map(Fmt::count).toList(), service.getTotalVehicleCount());
        fleet.setOnChange(n -> act(() -> service.setVehicleCount(n)));

        speed = new Ui.Segmented<>(SPEEDS, List.of("0.5×", "1×", "2×", "5×", "10×"), 1.0);
        speed.setOnChange(v -> {
            service.setSimulationSpeed(v);
            ctx.simulationChanged();
        });
        Tooltip.install(speed, new Tooltip("Playback speed (simulated seconds per real second)"));

        HBox lifecycle = group(chip, startBtn, pauseBtn, resumeBtn, stopBtn, resetBtn);
        HBox fleetGroup = group(Ui.label("Fleet", "toolbar-label"), fleet);
        // Installed on the group: a disabled control receives no mouse events, so it could not show it.
        Tooltip.install(fleetGroup, fleetTip);
        HBox speedGroup = group(Ui.label("Speed", "toolbar-label"), speed);
        getChildren().addAll(lifecycle, fleetGroup, speedGroup);

        ctx.onSimulationChanged(this::refresh);
        refresh();
    }

    private static HBox group(javafx.scene.Node... nodes) {
        HBox box = new HBox(6, nodes);
        box.getStyleClass().add("toolbar-group");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void act(Runnable action) {
        action.run();
        ctx.simulationChanged();
    }

    public void refresh() {
        SimulationState state = service.getSimulationState();
        chip.set(state);
        startBtn.setDisable(state != SimulationState.STOPPED);
        pauseBtn.setDisable(state != SimulationState.RUNNING);
        resumeBtn.setDisable(state != SimulationState.PAUSED);
        stopBtn.setDisable(state == SimulationState.STOPPED);
        resetBtn.setDisable(state == SimulationState.RUNNING);
        boolean canResize = state == SimulationState.STOPPED;
        fleet.setDisable(!canResize);
        fleetTip.setText(canResize ? "Number of simulated vehicles"
                : "Stop the simulation to change the fleet size");
        fleet.select(service.getTotalVehicleCount());
        speed.select(service.getSimulationSpeed());
    }
}
