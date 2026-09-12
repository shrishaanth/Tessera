package com.tessera.risk.reporting.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.risk.common.model.RiskAlert;
import com.tessera.risk.reporting.alert.AlertBroadcaster;
import com.tessera.risk.reporting.alert.AlertHistory;
import com.tessera.risk.reporting.alert.AlertStream;

/**
 * Recent alerts over HTTP, for a client that has just loaded (FR-5.3).
 *
 * <p>The live feed is the WebSocket at {@code /ws/alerts}; this is the catch-up. A
 * dashboard polling this instead would be slower and heavier for no gain, so it is
 * documented as history rather than as the primary route.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private static final int MAX_ALERTS = 200;

    private final AlertHistory history;
    private final AlertStream stream;
    private final AlertBroadcaster broadcaster;

    public AlertController(AlertHistory history, AlertStream stream,
                           AlertBroadcaster broadcaster) {
        this.history = history;
        this.stream = stream;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    public List<RiskAlert> recent(@RequestParam(defaultValue = "50") int limit) {
        return history.recent(Math.min(Math.max(limit, 1), MAX_ALERTS));
    }

    /**
     * Whether the feed is actually working.
     *
     * <p>An empty alert list is ambiguous — a calm fleet and a dead Kafka consumer
     * look identical from {@code /api/alerts}. This distinguishes them, which is the
     * difference between "nothing is wrong" and "we would not know if it were".
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "consumerRunning", stream.isRunning(),
                "alertsConsumed", stream.consumedCount(),
                "malformedDiscarded", stream.malformedCount(),
                "buffered", history.size(),
                "connectedDashboards", broadcaster.connectedClients());
    }
}
