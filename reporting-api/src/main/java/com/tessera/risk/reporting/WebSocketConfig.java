package com.tessera.risk.reporting;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.tessera.risk.reporting.alert.AlertBroadcaster;

/**
 * Exposes the alert feed at {@code /ws/alerts}.
 *
 * <p>Origins are unrestricted because the dashboard is served separately during
 * development and this stack is not deployed anywhere public. That is a development
 * convenience and would be wrong in a deployed service — noted here rather than
 * left to be discovered.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AlertBroadcaster broadcaster;

    public WebSocketConfig(AlertBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(broadcaster, "/ws/alerts").setAllowedOriginPatterns("*");
    }
}
