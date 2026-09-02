package com.tessera.fleet.geofence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tessera.fleet.durable.GeofenceEventRecord;

/**
 * The live geofencing state machine (FR-3.2–FR-3.5). Pure and self-contained: it
 * holds the site set and a per-(vehicle, site) visit state, and turns a stream of
 * position fixes into debounced enter/exit events, dwell times and dwell alerts.
 * No I/O — the caller ({@code GeofenceService}) persists and broadcasts what
 * {@link #evaluate} returns.
 *
 * <p>Debounce (FR-3.4): a boundary crossing is only acted on once the vehicle has
 * stayed on the new side for at least {@code debounceMillis}. A crossing that
 * reverses within that window is treated as GPS jitter and produces no event. The
 * event's timestamp and the dwell clock use the <em>first</em> fix on the new
 * side (the real crossing moment), not the confirmation moment.
 */
public class GeofenceEngine {

    private final long debounceMillis;
    private final long defaultDwellAlertMillis;

    private volatile List<Site> sites = List.of();
    private final Map<String, Map<String, VisitState>> visits = new HashMap<>();

    public GeofenceEngine(long debounceMillis, long defaultDwellAlertMillis) {
        this.debounceMillis = debounceMillis;
        this.defaultDwellAlertMillis = defaultDwellAlertMillis;
    }

    /** Replace the site set (called at startup and after any site CRUD). */
    public synchronized void reload(List<Site> newSites) {
        this.sites = List.copyOf(newSites);
        // Drop visit state for sites that no longer exist.
        for (Map<String, VisitState> perSite : visits.values()) {
            perSite.keySet().removeIf(siteId -> newSites.stream().noneMatch(s -> s.id().equals(siteId)));
        }
    }

    public List<Site> sites() {
        return sites;
    }

    /** One entry in {@link Result#dwellAlerts()}. */
    public record DwellAlert(String vehicleId, String siteId, String siteName, int dwellSeconds) { }

    /**
     * @param events        debounced ENTER/EXIT events to persist and broadcast
     * @param dwellAlerts   dwell-threshold breaches, at most one per visit (FR-3.5)
     * @param currentSiteId the site the vehicle is now inside (latest entry wins),
     *        or {@code null} if it is not inside any site
     */
    public record Result(List<GeofenceEventRecord> events, List<DwellAlert> dwellAlerts,
                         String currentSiteId) {
        static Result empty() {
            return new Result(List.of(), List.of(), null);
        }
    }

    public synchronized Result evaluate(String vehicleId, double lat, double lon, long nowMillis) {
        List<Site> snapshot = sites;
        if (snapshot.isEmpty()) {
            visits.remove(vehicleId);
            return Result.empty();
        }

        Map<String, VisitState> perSite = visits.computeIfAbsent(vehicleId, k -> new HashMap<>());
        List<GeofenceEventRecord> events = new ArrayList<>();
        List<DwellAlert> alerts = new ArrayList<>();
        String currentSiteId = null;
        long currentEnterMs = Long.MIN_VALUE;

        for (Site site : snapshot) {
            VisitState st = perSite.computeIfAbsent(site.id(), k -> new VisitState());
            boolean inside = site.contains(lat, lon);
            long dwellAlertMillis = site.dwellAlertSeconds() != null
                    ? site.dwellAlertSeconds() * 1000L
                    : defaultDwellAlertMillis;

            switch (st.phase) {
                case OUTSIDE -> {
                    if (inside) {
                        st.phase = VisitState.Phase.ENTERING;
                        st.pendingSinceMs = nowMillis;
                    }
                }
                case ENTERING -> {
                    if (!inside) {
                        st.reset(); // jitter — never really entered
                    } else if (nowMillis - st.pendingSinceMs >= debounceMillis) {
                        st.phase = VisitState.Phase.INSIDE;
                        st.enterMs = st.pendingSinceMs;
                        st.dwellAlerted = false;
                        events.add(GeofenceEventRecord.enter(vehicleId, site.id(), st.enterMs));
                    }
                }
                case INSIDE -> {
                    if (!inside) {
                        st.phase = VisitState.Phase.EXITING;
                        st.pendingSinceMs = nowMillis;
                    } else if (!st.dwellAlerted && dwellAlertMillis > 0
                            && nowMillis - st.enterMs >= dwellAlertMillis) {
                        st.dwellAlerted = true;
                        int dwell = (int) ((nowMillis - st.enterMs) / 1000);
                        alerts.add(new DwellAlert(vehicleId, site.id(), site.name(), dwell));
                    }
                }
                case EXITING -> {
                    if (inside) {
                        st.phase = VisitState.Phase.INSIDE; // jitter — never really left
                    } else if (nowMillis - st.pendingSinceMs >= debounceMillis) {
                        int dwellSec = (int) Math.max(0, (st.pendingSinceMs - st.enterMs) / 1000);
                        events.add(GeofenceEventRecord.exit(vehicleId, site.id(),
                                st.pendingSinceMs, dwellSec));
                        st.reset();
                    }
                }
                default -> { }
            }

            if (st.phase == VisitState.Phase.INSIDE && st.enterMs > currentEnterMs) {
                currentEnterMs = st.enterMs;
                currentSiteId = site.id();
            }
        }

        return new Result(events, alerts, currentSiteId);
    }

    /** Drop all visit state for a vehicle (e.g. when it goes offline). */
    public synchronized void forget(String vehicleId) {
        visits.remove(vehicleId);
    }

    static final class VisitState {
        enum Phase { OUTSIDE, ENTERING, INSIDE, EXITING }

        Phase phase = Phase.OUTSIDE;
        long pendingSinceMs;
        long enterMs;
        boolean dwellAlerted;

        void reset() {
            phase = Phase.OUTSIDE;
            pendingSinceMs = 0;
            enterMs = 0;
            dwellAlerted = false;
        }
    }
}
