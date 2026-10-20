package com.geotracker.fx;

import com.geotracker.fx.ui.PngWriter;
import com.geotracker.service.ApplicationService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Developer tool for visual verification: drives the running application through its pages and states
 * and writes a PNG snapshot of the real rendered scene (including the map) after each step, then exits.
 *
 * Enable with {@code -Dtessera.tour=<dir>} or the {@code TESSERA_TOUR_DIR} environment variable.
 */
public final class UiTour {

    private record Step(String name, Runnable action, long waitMillis) {}

    private final Stage stage;
    private final AppShell shell;
    private final AppContext ctx;
    private final Path dir;
    private final List<Step> steps = new ArrayList<>();
    private final List<Thread> writers = new ArrayList<>();
    private int index = 0;

    public UiTour(Stage stage, AppShell shell, AppContext ctx, Path dir) {
        this.stage = stage;
        this.shell = shell;
        this.ctx = ctx;
        this.dir = dir;
        String only = System.getProperty("tessera.tour.steps", System.getenv().getOrDefault("TESSERA_TOUR_STEPS", ""));
        build();
        if (!only.isBlank()) {
            List<String> keep = List.of(only.split(","));
            steps.removeIf(s -> s.name() != null && keep.stream().noneMatch(k -> s.name().startsWith(k)));
        }
    }

    private void build() {
        ApplicationService svc = ctx.service;
        add("01-live-initial", () -> {}, 8000);
        if (System.getenv("TESSERA_TILE_URL") != null) {
            add("27-tiles-failing", () -> {}, 14000);   // run with an unreachable TESSERA_TILE_URL
        }
        add("02-live-running-100", () -> {
            svc.setVehicleCount(100);
            svc.startSimulation();
            ctx.simulationChanged();
        }, 5000);
        add("03-live-selected", () -> ctx.selectedVehicle.set(42), 2000);
        add("04-dashboard", () -> shell.navigate("Dashboard"), 3500);
        add("05-vehicles", () -> shell.navigate("Vehicles"), 2500);
        add("06-routing", () -> {
            shell.navigate("Routing");
            page(RoutingPane.class).computeRouteForTour();
        }, 5000);
        add("07-geofencing", () -> shell.navigate("Geofencing"), 2500);
        add("08-search", () -> {
            shell.navigate("Spatial Search");
            page(SpatialSearchPane.class).runQueryForTour();
        }, 2500);
        add("09-performance", () -> shell.navigate("Performance"), 2500);
        add("10-settings", () -> shell.navigate("Settings"), 2000);
        add("11-live-roadgraph", () -> {
            shell.navigate("Live Map");
            ctx.map.setLayerVisible("roadGraph", true);
        }, 2500);
        add("12-live-small", () -> {
            ctx.map.setLayerVisible("roadGraph", false);
            stage.setWidth(1024);
            stage.setHeight(680);
        }, 3000);
        add("13-live-large", () -> {
            stage.setWidth(1680);
            stage.setHeight(1000);
        }, 3500);
        add("14-live-10000", () -> {
            stage.setWidth(1440);
            stage.setHeight(900);
            svc.stopSimulation();
            svc.setVehicleCount(10_000);
            svc.startSimulation();
            ctx.simulationChanged();
        }, 7000);
        add("15-live-10000-paused", () -> {
            svc.pauseSimulation();
            ctx.simulationChanged();
        }, 2500);
        add("16-performance-10000", () -> {
            svc.resumeSimulation();
            ctx.simulationChanged();
            shell.navigate("Performance");
        }, 5000);
        add("17-vehicles-10000", () -> shell.navigate("Vehicles"), 3000);
        add("18-live-1000", () -> {
            shell.navigate("Live Map");
            svc.stopSimulation();
            svc.setVehicleCount(1_000);
            svc.startSimulation();
            ctx.simulationChanged();
        }, 5000);
        add("19-offline-basemap", () -> ctx.map.setBasemap("offline"), 2500);
        add("20-live-stopped", () -> {
            ctx.map.setBasemap("osm");
            svc.stopSimulation();
            ctx.simulationChanged();
        }, 3000);
        // Real input path: JavaFX events -> WebView -> WebKit DOM events -> Leaflet.
        add("21-input-scroll-zoom", () -> {
            ctx.selectedVehicle.set(-1);
            String before = ctx.map.getStatusJson();
            WebView wv = ctx.map.webView();
            for (int i = 0; i < 6; i++) scroll(wv, wv.getWidth() / 2, wv.getHeight() / 2, 40);
            System.out.println("[tour] before scroll: " + before);
        }, 2500);
        add("22-input-drag-pan", () -> {
            System.out.println("[tour] before drag: " + ctx.map.getStatusJson());
            drag(ctx.map.webView(), 300, 200);
        }, 2500);
        add("26-zoom-transition", () -> {
            WebView wv = ctx.map.webView();
            for (int i = 0; i < 12; i++) scroll(wv, wv.getWidth() * 0.4, wv.getHeight() * 0.6, 40);
        }, 150);
        add("24-input-route-picking", () -> {
            shell.navigate("Routing");
            page(RoutingPane.class).beginPickingForTour();
            WebView wv = ctx.map.webView();
            PauseTransition a = new PauseTransition(Duration.millis(600));
            a.setOnFinished(e -> click(wv, wv.getWidth() * 0.30, wv.getHeight() * 0.35));
            PauseTransition b = new PauseTransition(Duration.millis(1200));
            b.setOnFinished(e -> click(wv, wv.getWidth() * 0.70, wv.getHeight() * 0.70));
            a.play();
            b.play();
        }, 4500);
        add("25-input-geofence-drawing", () -> {
            shell.navigate("Geofencing");
            page(GeofencePane.class).beginDrawingForTour("Tour zone");
            WebView wv = ctx.map.webView();
            double[][] pts = {{0.25, 0.25}, {0.55, 0.22}, {0.60, 0.60}, {0.28, 0.58}};
            for (int i = 0; i < pts.length; i++) {
                double[] p = pts[i];
                PauseTransition t = new PauseTransition(Duration.millis(700 + 350 * i));
                t.setOnFinished(e -> click(wv, wv.getWidth() * p[0], wv.getHeight() * p[1]));
                t.play();
            }
            PauseTransition done = new PauseTransition(Duration.millis(700 + 350 * pts.length + 300));
            done.setOnFinished(e -> page(GeofencePane.class).createZoneForTour());
            done.play();
        }, 6000);
        add("23-input-click-vehicle", () -> {
            ctx.map.fitVehicles();
            PauseTransition later = new PauseTransition(Duration.millis(400));
            later.setOnFinished(e -> {
                double[] p = ctx.map.vehiclePoint(7);
                if (p == null) {
                    System.err.println("[tour] vehicle 7 not on map");
                    return;
                }
                click(ctx.map.webView(), p[0], p[1]);
                System.out.println("[tour] clicked vehicle 7 at " + p[0] + "," + p[1]);
            });
            later.play();
        }, 2000);
        // City-scale coverage: 1,000 vehicles over the whole road network.
        add("28-city-1000", () -> {
            shell.navigate("Live Map");
            svc.stopSimulation();
            svc.setVehicleCount(1_000);
            svc.setSimulationSpeed(1.0);
            svc.startSimulation();
            ctx.simulationChanged();
            // Fit after the next layout pass: the map has just moved back into the full-width Live Map.
            PauseTransition fit = new PauseTransition(Duration.millis(400));
            fit.setOnFinished(e -> ctx.map.fitDataset());
            fit.play();
        }, 6000);
        add("29-city-1000-roadgraph", () -> ctx.map.setLayerVisible("roadGraph", true), 4000);
        add("31-city-10000", () -> {
            ctx.map.setLayerVisible("roadGraph", false);
            svc.stopSimulation();
            svc.setVehicleCount(10_000);
            svc.startSimulation();
            ctx.simulationChanged();
        }, 8000);
        add("30-geofence-demo", () -> {
            svc.stopSimulation();
            svc.setVehicleCount(1_000);
            svc.setSimulationSpeed(10.0);
            svc.startSimulation();
            ctx.simulationChanged();
            shell.navigate("Geofencing");
            PauseTransition later = new PauseTransition(Duration.millis(2500));
            later.setOnFinished(e -> page(GeofencePane.class).runDemoForTour(0));
            later.play();
        }, 16000);
    }

    // Event constructors take scene coordinates; the dispatch chain converts them to the target's local ones.
    private static void scroll(WebView wv, double x, double y, double deltaY) {
        javafx.geometry.Point2D sc = wv.localToScene(x, y), s = wv.localToScreen(x, y);
        wv.fireEvent(new ScrollEvent(ScrollEvent.SCROLL, sc.getX(), sc.getY(), s.getX(), s.getY(), false, false, false, false,
                false, false, 0, deltaY, 0, deltaY, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, new PickResult(wv, sc.getX(), sc.getY())));
    }

    private static MouseEvent mouse(WebView wv, javafx.event.EventType<MouseEvent> type, double x, double y, boolean down, int clicks) {
        javafx.geometry.Point2D sc = wv.localToScene(x, y), s = wv.localToScreen(x, y);
        return new MouseEvent(type, sc.getX(), sc.getY(), s.getX(), s.getY(), MouseButton.PRIMARY, clicks, false, false, false, false,
                down, false, false, false, false, false, new PickResult(wv, sc.getX(), sc.getY()));
    }

    /** Drags over ~400 ms like a real pointer (Leaflet derives inertia from event timing). */
    private static void drag(WebView wv, double dx, double dy) {
        double x = wv.getWidth() / 2, y = wv.getHeight() / 2;
        wv.fireEvent(mouse(wv, MouseEvent.MOUSE_MOVED, x, y, false, 0));
        wv.fireEvent(mouse(wv, MouseEvent.MOUSE_PRESSED, x, y, true, 1));
        javafx.animation.Timeline tl = new javafx.animation.Timeline();
        for (int i = 1; i <= 12; i++) {
            double fx = x + dx * i / 12, fy = y + dy * i / 12;
            tl.getKeyFrames().add(new javafx.animation.KeyFrame(Duration.millis(33.0 * i),
                    e -> wv.fireEvent(mouse(wv, MouseEvent.MOUSE_DRAGGED, fx, fy, true, 0))));
        }
        tl.getKeyFrames().add(new javafx.animation.KeyFrame(Duration.millis(33.0 * 12 + 120),
                e -> wv.fireEvent(mouse(wv, MouseEvent.MOUSE_RELEASED, x + dx, y + dy, false, 1))));
        tl.play();
    }

    private static void click(WebView wv, double x, double y) {
        wv.fireEvent(mouse(wv, MouseEvent.MOUSE_MOVED, x, y, false, 0));
        wv.fireEvent(mouse(wv, MouseEvent.MOUSE_PRESSED, x, y, true, 1));
        wv.fireEvent(mouse(wv, MouseEvent.MOUSE_RELEASED, x, y, false, 1));
        wv.fireEvent(mouse(wv, MouseEvent.MOUSE_CLICKED, x, y, false, 1));
    }

    private void add(String name, Runnable action, long waitMillis) {
        steps.add(new Step(name, action, waitMillis));
    }

    private <T> T page(Class<T> type) {
        return type.cast(shell.currentPage());
    }

    public void start() {
        System.out.println("[tour] writing " + steps.size() + " snapshots to " + dir.toAbsolutePath());
        next();
    }

    private void next() {
        if (index >= steps.size()) {
            List<Thread> pending = new ArrayList<>(writers);
            Thread waiter = new Thread(() -> {
                for (Thread w : pending) {
                    try {
                        w.join(30_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                System.out.println("[tour] done");
                Platform.runLater(Platform::exit);
            }, "tour-exit");
            waiter.start();
            return;
        }
        Step step = steps.get(index++);
        long actionStart = System.currentTimeMillis();
        try {
            step.action().run();
        } catch (RuntimeException e) {
            System.err.println("[tour] step " + step.name() + " failed: " + e);
        }
        long actionMs = System.currentTimeMillis() - actionStart;
        if (actionMs > 200) System.out.println("[tour] step " + step.name() + " action took " + actionMs + " ms on the FX thread");
        PauseTransition wait = new PauseTransition(Duration.millis(step.waitMillis()));
        wait.setOnFinished(e -> {
            capture(step.name());
            next();
        });
        wait.play();
    }

    private void capture(String name) {
        WritableImage image = stage.getScene().snapshot(null);
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        // Read the pixels here (snapshot images are tied to the FX/render threads); compress elsewhere so
        // verification does not stall the UI it is observing.
        byte[] pixels = PngWriter.pixels(image);
        System.out.println("[tour] " + name + " t=" + (System.currentTimeMillis() % 1_000_000) + " " + w + "x" + h + " page=" + shell.currentPage().title()
                + " selected=" + ctx.selectedVehicle.get() + " map=" + ctx.map.getStatusJson());
        Thread t = new Thread(() -> {
            try {
                PngWriter.write(pixels, w, h, dir.resolve(name + ".png"));
            } catch (Exception e) {
                System.err.println("[tour] capture " + name + " failed: " + e);
            }
        }, "tour-png");
        writers.add(t);
        t.start();
    }
}
