package com.tessera.fleet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tessera Fleet — Real-Time Dispatch &amp; Operations Platform.
 *
 * <p>Phase 1 scope (SRS §8): the live layer and dispatcher map — an in-memory
 * (Redis) geospatial index, a live fleet map fed over WebSocket, and
 * road-network travel-time-aware nearest-available-vehicle assignment
 * (FR-1, FR-2). The durable layer (Phase 2+) is intentionally absent here.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TesseraFleetApplication {

    public static void main(String[] args) {
        SpringApplication.run(TesseraFleetApplication.class, args);
    }
}
