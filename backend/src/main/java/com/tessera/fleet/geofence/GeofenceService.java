package com.tessera.fleet.geofence;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.tessera.fleet.alert.Alert;
import com.tessera.fleet.alert.AlertService;
import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.GeofenceEventRecord;
import org.springframework.context.ApplicationEventPublisher;

import com.tessera.fleet.durable.GeofenceEventRecord.Type;
import com.tessera.fleet.durable.WriteBehindService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.web.ws.LiveWebSocketHandler;

/**
 * Connects the pure {@link GeofenceEngine} to the rest of the system: it feeds
 * each position fix in, then persists (write-behind) and broadcasts the debounced
 * enter/exit events, raises dwell alerts (FR-3.5), and reflects on-site state into
 * the live layer so the vehicle shows as ON_SITE on the map (FR-1.1).
 */
@Service
public class GeofenceService {

    private static final Logger log = LoggerFactory.getLogger(GeofenceService.class);

    private final GeofenceEngine engine;
    private final DurableStore durableStore;
    private final WriteBehindService writeBehind;
    private final LiveFleetService liveFleet;
    private final AlertService alertService;
    private final LiveWebSocketHandler webSocket;
    private final ApplicationEventPublisher events;

    private final Map<String, String> siteNames = new ConcurrentHashMap<>();
    private final Map<String, String> lastOnSite = new ConcurrentHashMap<>();

    public GeofenceService(GeofenceEngine engine, DurableStore durableStore,
                           WriteBehindService writeBehind, LiveFleetService liveFleet,
                           AlertService alertService, LiveWebSocketHandler webSocket,
                           ApplicationEventPublisher events) {
        this.engine = engine;
        this.durableStore = durableStore;
        this.writeBehind = writeBehind;
        this.liveFleet = liveFleet;
        this.alertService = alertService;
        this.webSocket = webSocket;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadSitesOnStartup() {
        reloadSites();
    }

    /** Rebuild the engine's site set from the durable store (after any site CRUD). */
    public void reloadSites() {
        List<Site> sites = durableStore.loadSites().stream().map(Site::fromRecord).toList();
        siteNames.clear();
        sites.forEach(s -> siteNames.put(s.id(), s.name()));
        engine.reload(sites);
        log.info("Geofence engine loaded {} site(s)", sites.size());
    }

    public List<Site> sites() {
        return engine.sites();
    }

    /** Called by the ingestion loop for every position fix. */
    public void onPosition(String vehicleId, double lat, double lon, long epochMillis) {
        GeofenceEngine.Result result = engine.evaluate(vehicleId, lat, lon, epochMillis);

        for (GeofenceEventRecord event : result.events()) {
            writeBehind.offerGeofenceEvent(event);
            webSocket.broadcastGeofenceEvent(event, siteNames.get(event.siteId()));
            if (event.type() == Type.ENTER) {
                events.publishEvent(new GeofenceEnteredEvent(
                        event.vehicleId(), event.siteId(), event.epochMillis()));
            }
        }
        for (GeofenceEngine.DwellAlert dwell : result.dwellAlerts()) {
            alertService.raise(Alert.Type.DWELL_EXCEEDED, Alert.Severity.WARNING,
                    dwell.vehicleId(), dwell.siteId(),
                    dwell.vehicleId() + " has been on site \"" + dwell.siteName() + "\" for "
                            + formatDuration(dwell.dwellSeconds()));
        }

        String previous = lastOnSite.get(vehicleId);
        String current = result.currentSiteId();
        if (!Objects.equals(previous, current)) {
            liveFleet.setOnSite(vehicleId, current);
            if (current == null) {
                lastOnSite.remove(vehicleId);
            } else {
                lastOnSite.put(vehicleId, current);
            }
        }
    }

    static String formatDuration(int seconds) {
        if (seconds < 90) {
            return seconds + " sec";
        }
        int m = seconds / 60;
        if (m < 60) {
            return m + " min";
        }
        return (m / 60) + "h " + (m % 60) + "m";
    }
}
