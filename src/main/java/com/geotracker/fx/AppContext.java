package com.geotracker.fx;

import com.geotracker.service.ApplicationService;
import com.geotracker.service.ApplicationService.VehicleInfo;
import com.geotracker.service.ApplicationService.ZoneView;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** State shared by all pages: backend, map, live data, vehicle selection and navigation. */
public final class AppContext {

    /** Zone outline colours (validated categorical palette, skipping the vehicle blue/orange). */
    private static final String[] ZONE_COLORS = {"#1baf7a", "#6250d6", "#d55181", "#c98500", "#008300", "#e34948"};

    public final ApplicationService service;
    public final MapPane map;
    /** Selected vehicle id, or -1. */
    public final LongProperty selectedVehicle = new SimpleLongProperty(-1);
    public final ObjectProperty<LiveData.Frame> lastFrame = new SimpleObjectProperty<>();
    private final List<Runnable> simulationListeners = new ArrayList<>();
    private Consumer<String> navigator = id -> {};
    private Consumer<String> linkOpener = url -> {};
    private LiveData liveData;
    private String zonesSignature = "";

    public AppContext(ApplicationService service, MapPane map) {
        this.service = service;
        this.map = map;
    }

    void setNavigator(Consumer<String> navigator) { this.navigator = navigator; }
    public void setLiveData(LiveData liveData) { this.liveData = liveData; }
    public void setLinkOpener(Consumer<String> opener) { this.linkOpener = opener; }

    public void navigate(String pageTitle) { navigator.accept(pageTitle); }

    public void openLink(String url) { linkOpener.accept(url); }

    public LiveData liveData() { return liveData; }

    /** Call after any simulation control so every view refreshes immediately. */
    public void simulationChanged() {
        simulationListeners.forEach(Runnable::run);
        if (liveData != null) liveData.refreshNow();
    }

    public void onSimulationChanged(Runnable listener) {
        simulationListeners.add(listener);
    }

    public static String zoneColor(int index) {
        return ZONE_COLORS[Math.floorMod(index, ZONE_COLORS.length)];
    }

    /** Pushes zone outlines (with current occupancy) to the map if anything changed. */
    public void syncZonesToMap(boolean force) {
        List<ZoneView> zones = service.getZones();
        StringBuilder sig = new StringBuilder();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < zones.size(); i++) {
            ZoneView z = zones.get(i);
            sig.append(z.id()).append(':').append(z.occupancy()).append(';');
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(MapPayloads.jsonString(z.id()))
              .append(",\"name\":").append(MapPayloads.jsonString(z.name()))
              .append(",\"color\":").append(MapPayloads.jsonString(zoneColor(i)))
              .append(",\"builtIn\":").append(z.builtIn())
              .append(",\"occupancy\":").append(z.occupancy())
              .append(",\"points\":").append(MapPayloads.latLngs(z.polygon()))
              .append('}');
        }
        sb.append(']');
        if (force || !sig.toString().equals(zonesSignature)) {
            zonesSignature = sig.toString();
            map.setZones(sb.toString());
        }
    }

    /** Finds a vehicle in a frame's id-ordered list by binary search. */
    public static VehicleInfo find(List<VehicleInfo> vehicles, long id) {
        int lo = 0, hi = vehicles.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = vehicles.get(mid).id();
            if (v == id) return vehicles.get(mid);
            if (v < id) lo = mid + 1; else hi = mid - 1;
        }
        return null;
    }

    public static String sourceLabel(String source) {
        return ApplicationService.SOURCE_CSV.equals(source) ? "CSV replay" : "Road graph";
    }
}
