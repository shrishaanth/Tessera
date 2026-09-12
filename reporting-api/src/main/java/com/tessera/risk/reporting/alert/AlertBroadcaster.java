package com.tessera.risk.reporting.alert;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.tessera.risk.common.model.RiskAlert;

/**
 * Pushes alerts to connected dashboard clients (FR-4.2).
 *
 * <p>A raw WebSocket handler rather than STOMP. There is one message type flowing
 * one way, so a broker, destinations and subscriptions would be machinery with
 * nothing to carry.
 *
 * <p>On connect a client is sent the recent history immediately, so a dashboard
 * opened after an alert was raised still shows it. Without that, a freshly loaded
 * page looks like a fleet with nothing wrong until the next alert happens to fire,
 * which could be minutes.
 */
@Component
public class AlertBroadcaster extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertBroadcaster.class);

    /**
     * Copy-on-write because the set is read on every alert and written only when a
     * client connects or leaves — and iteration must not throw while a broadcast is
     * in flight and a browser tab closes.
     */
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final AlertHistory history;

    public AlertBroadcaster(AlertHistory history) {
        this.history = history;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Dashboard connected ({} total)", sessions.size());
        for (RiskAlert alert : history.recent(25)) {
            send(session, alert);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Dashboard disconnected ({} remaining)", sessions.size());
    }

    /** Fan out to every connected client. Returns how many were reached. */
    public int broadcast(RiskAlert alert) {
        int delivered = 0;
        for (WebSocketSession session : sessions) {
            if (send(session, alert)) {
                delivered++;
            }
        }
        return delivered;
    }

    private boolean send(WebSocketSession session, RiskAlert alert) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return false;
        }
        try {
            // Synchronized on the session: Spring's WebSocketSession is not
            // thread-safe for concurrent sends, and the history replay on connect can
            // overlap a broadcast from the Kafka thread.
            synchronized (session) {
                session.sendMessage(new TextMessage(alert.toJson()));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            // A client that vanished mid-send is ordinary, not exceptional. Drop it
            // and carry on rather than failing the whole broadcast.
            log.debug("Dropping dashboard session {}: {}", session.getId(), e.getMessage());
            sessions.remove(session);
            return false;
        }
    }

    public int connectedClients() {
        return sessions.size();
    }
}
