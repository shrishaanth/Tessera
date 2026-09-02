package com.tessera.fleet.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serve the built React SPA (packaged under {@code static/}) for client-side
 * routes. API, WebSocket and actuator paths are left untouched; anything else
 * that is not a real static file falls through to {@code index.html}.
 */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        // Single-segment and nested client routes (e.g. /map, /reports/replay).
        registry.addViewController("/{path:^(?!api$|ws$|actuator$|assets$|index\\.html$).*}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!api$|ws$|actuator$|assets$).*}/{sub:[^\\.]*}")
                .setViewName("forward:/index.html");
    }
}
