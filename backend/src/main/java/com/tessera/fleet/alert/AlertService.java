package com.tessera.fleet.alert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.tessera.fleet.web.ws.LiveWebSocketHandler;

/**
 * Holds the rolling dispatcher alert feed and pushes new alerts to connected
 * clients. In-memory and capped — alerts are operational signal, not a system of
 * record. Phase 2 raises one alert type: dwell-time exceeded (FR-3.5).
 */
@Service
public class AlertService {

    private static final int MAX_ALERTS = 500;

    private final LiveWebSocketHandler webSocket;
    private final Deque<Alert> alerts = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    public AlertService(LiveWebSocketHandler webSocket) {
        this.webSocket = webSocket;
    }

    public synchronized Alert raise(Alert.Type type, Alert.Severity severity,
                                    String vehicleId, String siteId, String message) {
        Alert alert = new Alert("ALERT-" + sequence.incrementAndGet(), type, severity,
                vehicleId, siteId, message, System.currentTimeMillis(), false);
        alerts.addFirst(alert);
        while (alerts.size() > MAX_ALERTS) {
            alerts.removeLast();
        }
        webSocket.broadcastAlert(alert);
        return alert;
    }

    public synchronized List<Alert> list(boolean includeAcknowledged) {
        List<Alert> out = new ArrayList<>();
        for (Alert a : alerts) {
            if (includeAcknowledged || !a.acknowledged()) {
                out.add(a);
            }
        }
        return out;
    }

    public synchronized Optional<Alert> acknowledge(String id) {
        Alert[] arr = alerts.toArray(new Alert[0]);
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].id().equals(id) && !arr[i].acknowledged()) {
                Alert acked = arr[i].acknowledgedCopy();
                arr[i] = acked;
                alerts.clear();
                for (Alert a : arr) {
                    alerts.addLast(a);
                }
                return Optional.of(acked);
            }
        }
        return Optional.empty();
    }

    public synchronized int unacknowledgedCount() {
        return (int) alerts.stream().filter(a -> !a.acknowledged()).count();
    }

    /** Test hook. */
    public synchronized void clear() {
        alerts.clear();
    }
}
