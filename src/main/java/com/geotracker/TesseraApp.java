package com.geotracker;

import com.geotracker.fx.*;
import com.geotracker.service.ApplicationService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;

public class TesseraApp extends Application {

    private ApplicationService service;
    private LiveData liveData;

    @Override
    public void start(Stage stage) {
        service = new ApplicationService();
        MapPane map = new MapPane();
        AppContext ctx = new AppContext(service, map);

        ctx.setLinkOpener(url -> getHostServices().showDocument(url));
        map.setOnOpenLink(ctx::openLink);
        map.setOnVehicleClicked(id -> ctx.selectedVehicle.set(id));
        ctx.selectedVehicle.addListener((obs, o, n) -> map.selectVehicle(n.longValue(), false));
        // TESSERA_TILE_URL overrides the tile server (e.g. a self-hosted or cached OSM tile server).
        String tileUrl = System.getenv("TESSERA_TILE_URL");
        map.initialize("{\"datasetBounds\":" + MapPayloads.bounds(service.getDatasetBounds())
                + (tileUrl != null && !tileUrl.isBlank() ? ",\"tileUrl\":" + MapPayloads.jsonString(tileUrl) : "") + "}");
        map.setRoadNetwork(MapPayloads.roadPolylines(service.getRoadPolylines()));
        ctx.syncZonesToMap(true);

        List<Page> pages = List.of(
                new DashboardPane(ctx),
                new LiveMapPane(ctx),
                new VehicleExplorerPane(ctx),
                new RoutingPane(ctx),
                new GeofencePane(ctx),
                new SpatialSearchPane(ctx),
                new PerformancePane(ctx),
                new SettingsPane(ctx));
        AppShell shell = new AppShell(ctx, pages);

        liveData = new LiveData(service, shell::onFrame);
        ctx.setLiveData(liveData);

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.min(1440, screen.getWidth() * 0.94);
        double height = Math.min(900, screen.getHeight() * 0.94);
        Scene scene = new Scene(shell, width, height);
        scene.getStylesheets().add(TesseraApp.class.getResource("/styles/tessera.css").toExternalForm());

        stage.setTitle("Tessera — Fleet Tracking");
        stage.setScene(scene);
        stage.setMinWidth(1024);
        stage.setMinHeight(680);
        stage.setOnCloseRequest(e -> Platform.exit());
        stage.show();

        shell.navigate("Live Map");
        liveData.start();

        String tourDir = System.getProperty("tessera.tour", System.getenv("TESSERA_TOUR_DIR"));
        if (tourDir != null && !tourDir.isBlank()) {
            new UiTour(stage, shell, ctx, Path.of(tourDir)).start();
        }
    }

    @Override
    public void stop() {
        if (liveData != null) liveData.stop();
        if (service != null) service.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
