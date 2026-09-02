package com.tessera.fleet.geofence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.tessera.fleet.config.FleetProperties;

@Configuration
public class GeofenceConfig {

    @Bean
    public GeofenceEngine geofenceEngine(FleetProperties properties) {
        FleetProperties.Geofence cfg = properties.geofence();
        return new GeofenceEngine(
                cfg.debounceSeconds() * 1000L,
                cfg.defaultDwellAlertSeconds() * 1000L);
    }
}
