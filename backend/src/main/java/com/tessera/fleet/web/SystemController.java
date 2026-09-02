package com.tessera.fleet.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.ingestion.IngestionService;
import com.tessera.fleet.routing.TravelTimeService;
import com.tessera.fleet.web.ws.LiveWebSocketHandler;

/**
 * Operational snapshot of the live layer — useful for the "system first" ethos of
 * the product and for smoke-checking a running deployment.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final IngestionService ingestion;
    private final LiveWebSocketHandler webSocket;
    private final TravelTimeService travelTime;

    public SystemController(IngestionService ingestion, LiveWebSocketHandler webSocket,
                            TravelTimeService travelTime) {
        this.ingestion = ingestion;
        this.webSocket = webSocket;
        this.travelTime = travelTime;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "positionSource", Map.of(
                        "id", ingestion.source().id(),
                        "name", ingestion.source().displayName(),
                        "substitute", ingestion.source().isSubstitute()),
                "ingestion", Map.of(
                        "appliedTotal", ingestion.appliedTotal(),
                        "rejectedTotal", ingestion.rejectedTotal(),
                        "lastBatchEpochMs", ingestion.lastBatchEpochMs(),
                        "lastBatchSize", ingestion.lastBatchSize()),
                "webSocketConnections", webSocket.connectionCount(),
                "roadGraph", Map.of(
                        "area", travelTime.graph().areaName(),
                        "nodes", travelTime.graph().nodeCount(),
                        "edges", travelTime.graph().edgeCount()));
    }
}
