package com.tessera.fleet.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.WriteBehindService;
import com.tessera.fleet.geofence.GeofenceService;
import com.tessera.fleet.ingestion.IngestionService;
import com.tessera.fleet.routing.TravelTimeService;
import com.tessera.fleet.web.ws.LiveWebSocketHandler;

/**
 * Operational snapshot of the live and durable layers — useful for the "system
 * first" ethos of the product and for smoke-checking a running deployment.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final IngestionService ingestion;
    private final LiveWebSocketHandler webSocket;
    private final TravelTimeService travelTime;
    private final WriteBehindService writeBehind;
    private final DurableStore durableStore;
    private final GeofenceService geofenceService;

    public SystemController(IngestionService ingestion, LiveWebSocketHandler webSocket,
                            TravelTimeService travelTime, WriteBehindService writeBehind,
                            DurableStore durableStore, GeofenceService geofenceService) {
        this.ingestion = ingestion;
        this.webSocket = webSocket;
        this.travelTime = travelTime;
        this.writeBehind = writeBehind;
        this.durableStore = durableStore;
        this.geofenceService = geofenceService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        WriteBehindService.Stats wb = writeBehind.stats();
        Map<String, Object> out = new HashMap<>();
        out.put("positionSource", Map.of(
                "id", ingestion.source().id(),
                "name", ingestion.source().displayName(),
                "substitute", ingestion.source().isSubstitute()));
        out.put("ingestion", Map.of(
                "appliedTotal", ingestion.appliedTotal(),
                "rejectedTotal", ingestion.rejectedTotal(),
                "lastBatchEpochMs", ingestion.lastBatchEpochMs(),
                "lastBatchSize", ingestion.lastBatchSize()));
        out.put("webSocketConnections", webSocket.connectionCount());
        out.put("roadGraph", Map.of(
                "area", travelTime.graph().areaName(),
                "nodes", travelTime.graph().nodeCount(),
                "edges", travelTime.graph().edgeCount()));
        out.put("geofence", Map.of("siteCount", geofenceService.sites().size()));
        out.put("durable", Map.of(
                "store", durableStore.getClass().getSimpleName(),
                "healthy", wb.healthy(),
                "enqueuedPositions", wb.enqueuedPositions(),
                "writtenPositions", wb.writtenPositions(),
                "queueDepth", wb.queueDepth(),
                "droppedFull", wb.droppedFull(),
                "droppedError", wb.droppedError(),
                "writeFailures", wb.writeFailures()));
        return out;
    }
}
