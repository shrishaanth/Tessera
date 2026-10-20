package com.geotracker.fx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * The Leaflet map (WebView) and its Java API. A single instance is shared by every page that shows a
 * map; pages move it into their own layout with {@link #attachTo(StackPane)}.
 *
 * All methods must be called on the JavaFX application thread. Calls made before the page has loaded
 * are queued and replayed once it is ready.
 */
public class MapPane extends StackPane {

    public static final String USER_AGENT_SUFFIX = " Tessera/1.0 (JavaFX desktop fleet tracker)";

    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();
    // The JS side only holds a weak reference to bridge objects, so keep a strong one here.
    private final Bridge bridge = new Bridge();
    private final List<String> queued = new ArrayList<>();
    private final PauseTransition resizeDebounce = new PauseTransition(Duration.millis(60));
    private boolean ready = false;
    private String initScript;

    private LongConsumer onVehicleClicked = id -> {};
    private BiConsumer<String, double[]> onPick = (mode, latLng) -> {};
    private Runnable onEscape = () -> {};
    private Consumer<String> onOpenLink = url -> {};
    private Consumer<String> onTileState = s -> {};
    private volatile String tileDiagnostic = "";

    public MapPane() {
        getStyleClass().add("map-pane");
        webView.setContextMenuEnabled(false);
        webView.setMinSize(0, 0);
        getChildren().add(webView);
        setMinSize(0, 0);

        engine.setUserAgent(engine.getUserAgent() + USER_AGENT_SUFFIX);
        engine.setCreatePopupHandler(features -> null);
        engine.setOnAlert(e -> System.out.println("[map] alert: " + e.getData()));

        resizeDebounce.setOnFinished(e -> call("TesseraMap.invalidateSize()"));
        widthProperty().addListener((obs, o, n) -> resizeDebounce.playFromStart());
        heightProperty().addListener((obs, o, n) -> resizeDebounce.playFromStart());

        engine.getLoadWorker().stateProperty().addListener((obs, old, s) -> {
            if (s == Worker.State.SUCCEEDED) {
                onPageLoaded();
            } else if (s == Worker.State.FAILED) {
                Throwable ex = engine.getLoadWorker().getException();
                System.err.println("[map] page failed to load: " + (ex != null ? ex.getMessage() : "unknown"));
            }
        });
        engine.load(MapPane.class.getResource("/map/map.html").toExternalForm());
    }

    private void onPageLoaded() {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember("tesseraBridge", bridge);
            Object ok = engine.executeScript("window.TesseraMap ? TesseraMap.ready() : 'missing'");
            if (!"ok".equals(String.valueOf(ok))) {
                System.err.println("[map] Leaflet integration did not initialise: " + ok);
                return;
            }
        } catch (RuntimeException ex) {
            System.err.println("[map] bridge setup failed: " + ex.getMessage());
            return;
        }
        ready = true;
        if (initScript != null) exec(initScript);
        List<String> replay = new ArrayList<>(queued);
        queued.clear();
        replay.forEach(this::exec);
        invalidateSoon();
    }

    public boolean isReady() {
        return ready;
    }

    /** Moves the shared map into the given container (as its bottom-most child). */
    public void attachTo(StackPane host) {
        if (getParent() == host) {
            invalidateSoon();
            return;
        }
        if (getParent() instanceof StackPane old) {
            old.getChildren().remove(this);
        }
        host.getChildren().add(0, this);
        invalidateSoon();
    }

    /** Re-measures the map after the current layout pass (tab switch, re-parenting, window resize). */
    public void invalidateSoon() {
        Platform.runLater(() -> Platform.runLater(() -> call("TesseraMap.invalidateSize()")));
        resizeDebounce.playFromStart();
    }

    // ------------------------------------------------------------------ typed API

    public void initialize(String configJson) {
        initScript = "TesseraMap.init(" + MapPayloads.jsArgument(configJson) + ")";
        if (ready) exec(initScript);
    }

    /** Latest fleet positions. Not queued while loading: only the most recent frame matters. */
    public void setVehicles(String json) {
        if (ready) exec("TesseraMap.setVehicles(" + MapPayloads.jsArgument(json) + ")");
    }
    public void selectVehicle(long id, boolean centre) { call("TesseraMap.selectVehicle(" + id + "," + centre + ")"); }
    public void followVehicle(long id) { call("TesseraMap.followVehicle(" + id + ")"); }
    public void setLayerVisible(String layer, boolean visible) {
        call("TesseraMap.setLayerVisible(" + MapPayloads.jsonString(layer) + "," + visible + ")");
    }
    public void setRoadNetwork(String json) { call("TesseraMap.setRoadNetwork(" + MapPayloads.jsArgument(json) + ")"); }
    public void setBasemap(String mode) { call("TesseraMap.setBasemap(" + MapPayloads.jsonString(mode) + ")"); }
    public void retryTiles() { call("TesseraMap.retryTiles()"); }
    public void setZones(String json) { call("TesseraMap.setZones(" + MapPayloads.jsArgument(json) + ")"); }
    public void highlightZone(String id) { call("TesseraMap.highlightZone(" + (id == null ? "null" : MapPayloads.jsonString(id)) + ")"); }
    public void fitZone(String id) { call("TesseraMap.fitZone(" + MapPayloads.jsonString(id) + ")"); }
    public void setDraft(String json) { call("TesseraMap.setDraft(" + MapPayloads.jsArgument(json) + ")"); }
    public void setRoute(String json) { call("TesseraMap.setRoute(" + MapPayloads.jsArgument(json) + ")"); }
    public void setSearchArea(String json) { call("TesseraMap.setSearchArea(" + (json == null ? "null" : MapPayloads.jsArgument(json)) + ")"); }
    public void setSearchResults(String json) { call("TesseraMap.setSearchResults(" + MapPayloads.jsArgument(json) + ")"); }
    public void setPickMode(String mode) { call("TesseraMap.setPickMode(" + (mode == null ? "null" : MapPayloads.jsonString(mode)) + ")"); }
    public void fitDataset() { call("TesseraMap.fitDataset()"); }
    public void fitVehicles() { call("TesseraMap.fitVehicles()"); }
    public void fitBounds(double south, double west, double north, double east) {
        call("TesseraMap.fitBounds(" + south + "," + west + "," + north + "," + east + ")");
    }

    /** Current view as [south, west, north, east], or null if the map is not ready. */
    public double[] getViewBounds() {
        if (!ready) return null;
        try {
            String json = String.valueOf(engine.executeScript("TesseraMap.getViewBounds()"));
            String[] parts = json.replace("[", "").replace("]", "").split(",");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3])};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** JSON status (zoom, tile counters, draw time) from the page, or null if not ready. */
    public String getStatusJson() {
        if (!ready) return null;
        try {
            return String.valueOf(engine.executeScript("TesseraMap.getStatus()"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String getTileDiagnostic() {
        return tileDiagnostic;
    }

    /** Pixel position of a vehicle inside the map view, or null. Used by the UI tour. */
    double[] vehiclePoint(long id) {
        if (!ready) return null;
        String s = String.valueOf(engine.executeScript("TesseraMap.vehiclePoint(" + id + ")"));
        if (s.isEmpty() || !s.contains(",")) return null;
        String[] p = s.split(",");
        return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
    }

    WebView webView() {
        return webView;
    }

    // ------------------------------------------------------------------ listeners

    public void setOnVehicleClicked(LongConsumer handler) { this.onVehicleClicked = handler; }
    public void setOnPick(BiConsumer<String, double[]> handler) { this.onPick = handler; }
    public void setOnEscape(Runnable handler) { this.onEscape = handler; }
    public void setOnOpenLink(Consumer<String> handler) { this.onOpenLink = handler; }
    public void setOnTileState(Consumer<String> handler) { this.onTileState = handler; }

    // ------------------------------------------------------------------ internals

    private void call(String script) {
        if (!ready) {
            queued.add(script);
            return;
        }
        exec(script);
    }

    private void exec(String script) {
        try {
            engine.executeScript(script);
        } catch (RuntimeException e) {
            String head = script.length() > 80 ? script.substring(0, 80) + "..." : script;
            System.err.println("[map] script failed: " + head + " -> " + e.getMessage());
        }
    }

    private void handleMessage(String message) {
        String[] parts = message.split("\\|", 4);
        switch (parts[0]) {
            case "vehicle" -> onVehicleClicked.accept(Long.parseLong(parts[1]));
            case "pick" -> onPick.accept(parts[1],
                    new double[]{Double.parseDouble(parts[2]), Double.parseDouble(parts[3])});
            case "key" -> {
                if ("Escape".equals(parts[1])) onEscape.run();
            }
            case "open" -> onOpenLink.accept(message.substring("open|".length()));
            case "tiles" -> onTileState.accept(parts[1]);
            case "tileerror" -> diagnoseTile(message.substring("tileerror|".length()));
            case "log" -> {
                String level = parts.length > 1 ? parts[1] : "log";
                String text = parts.length > 2 ? message.substring(message.indexOf('|', 4) + 1) : "";
                (level.equals("error") ? System.err : System.out).println("[map " + level + "] " + text);
            }
            case "ready", "view" -> { }
            default -> System.out.println("[map] " + message);
        }
    }

    /**
     * An &lt;img&gt; error event carries no HTTP status, so fetch the failing tile once from Java to find out
     * why (DNS failure, timeout, HTTP 403/429, ...). Runs off the FX thread.
     */
    private void diagnoseTile(String url) {
        String clean = url.contains("#") ? url.substring(0, url.indexOf('#')) : url;
        String ua = engine.getUserAgent();
        Thread t = new Thread(() -> {
            String result;
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(clean))
                        .timeout(java.time.Duration.ofSeconds(8)).header("User-Agent", ua).GET().build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                result = resp.statusCode() == 200 ? "server reachable, HTTP 200 on retry" : "HTTP " + resp.statusCode();
            } catch (java.net.http.HttpConnectTimeoutException e) {
                result = "connection timed out";
            } catch (java.net.ConnectException e) {
                result = "no connection";
            } catch (java.io.IOException e) {
                result = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                result = e.getMessage();
            }
            String text = result;
            System.err.println("[map] tile request failed: " + clean + " -> " + text);
            Platform.runLater(() -> {
                tileDiagnostic = text;
                call("TesseraMap.setTileDiagnostic(" + MapPayloads.jsonString(text) + ")");
            });
        }, "tile-diagnostic");
        t.setDaemon(true);
        t.start();
    }

    /** Receives messages from tessera-map.js. Called by WebKit on the FX thread. */
    public final class Bridge {
        public void post(String message) {
            // Defer so handlers never re-enter the script engine while it is executing.
            Platform.runLater(() -> {
                try {
                    handleMessage(message);
                } catch (RuntimeException e) {
                    System.err.println("[map] bad message '" + message + "': " + e.getMessage());
                }
            });
        }
    }
}
