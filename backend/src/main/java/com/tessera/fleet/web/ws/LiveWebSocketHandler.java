package com.tessera.fleet.web.ws;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.tessera.fleet.alert.Alert;
import com.tessera.fleet.durable.GeofenceEventRecord;
import com.tessera.fleet.model.Vehicle;

/**
 * Pushes live fleet snapshots to connected dispatcher clients (SRS §5.3 — live
 * updates over WebSocket). One JSON frame per broadcast tick carrying every
 * vehicle's resolved status and position; at a few hundred vehicles this is a
 * few KB and keeps the UI within the 2-second freshness bound (FR-1.2 / NFR-2).
 *
 * <p>The handshake is a normal HTTP GET and is covered by the security filter
 * chain, so only authenticated sessions reach here (NFR-7).
 */
@Component
public class LiveWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper objectMapper;

    public LiveWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.debug("WS connected: {} ({} live)", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.debug("WS closed: {} ({} live)", session.getId(), sessions.size());
    }

    public int connectionCount() {
        return sessions.size();
    }

    /** Serialize and fan out a fleet snapshot to every open session. */
    public void broadcastFleet(List<Vehicle> vehicles) {
        broadcast(Map.of(
                "type", "fleet",
                "ts", System.currentTimeMillis(),
                "vehicles", vehicles));
    }

    /** Push a debounced geofence enter/exit as it happens (FR-3.2). */
    public void broadcastGeofenceEvent(GeofenceEventRecord event, String siteName) {
        broadcast(Map.of(
                "type", "geofence",
                "ts", System.currentTimeMillis(),
                "event", Map.of(
                        "vehicleId", event.vehicleId(),
                        "siteId", event.siteId(),
                        "siteName", siteName == null ? "" : siteName,
                        "eventType", event.type().name(),
                        "epochMillis", event.epochMillis(),
                        "dwellSeconds", event.dwellSeconds() == null ? -1 : event.dwellSeconds())));
    }

    /** Push a new dispatcher alert (FR-3.5). */
    public void broadcastAlert(Alert alert) {
        broadcast(Map.of("type", "alert", "ts", System.currentTimeMillis(), "alert", alert));
    }

    private void broadcast(Object payloadObject) {
        if (sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadObject);
        } catch (IOException e) {
            log.warn("Failed to serialize WS frame: {}", e.toString());
            return;
        }
        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                }
            } catch (Exception e) {
                log.debug("WS send failed for {}: {}", session.getId(), e.toString());
                sessions.remove(session);
            }
        }
    }
}
