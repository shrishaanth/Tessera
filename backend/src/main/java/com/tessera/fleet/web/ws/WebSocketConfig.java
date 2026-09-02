package com.tessera.fleet.web.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveWebSocketHandler liveHandler;

    public WebSocketConfig(LiveWebSocketHandler liveHandler) {
        this.liveHandler = liveHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveHandler, "/ws/live")
                // Dev: the Vite dev server proxies same-origin, but allow explicit
                // localhost origins too. Tighten for production deployment.
                .setAllowedOriginPatterns("*");
    }
}
