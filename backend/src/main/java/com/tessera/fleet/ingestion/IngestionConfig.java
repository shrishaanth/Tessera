package com.tessera.fleet.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.routing.TravelTimeService;

/** Wires the single active {@link PositionSource} from configuration. */
@Configuration
public class IngestionConfig {

    private static final Logger log = LoggerFactory.getLogger(IngestionConfig.class);

    @Bean
    public PositionSource positionSource(FleetProperties properties, TravelTimeService travelTime,
                                        ApplicationEventPublisher events) {
        PositionSource source = switch (properties.positionSource()) {
            case SIMULATOR -> new SimulatedPositionSource(
                    travelTime.graph(), properties.simulator(),
                    vehicleId -> events.publishEvent(
                            new VehicleArrivedEvent(vehicleId, System.currentTimeMillis())));
            case GTFS_REALTIME -> new GtfsRealtimePositionSource(properties.gtfs());
        };
        log.info("Active position source: {} ({}). {}",
                source.id(), source.displayName(), source.disclosure());
        return source;
    }
}
