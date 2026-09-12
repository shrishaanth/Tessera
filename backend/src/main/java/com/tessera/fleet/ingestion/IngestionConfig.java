package com.tessera.fleet.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.dataset.FleetDataset;

/**
 * Wires the single active {@link PositionSource} from configuration (SRS §2.6).
 *
 * <p>Two sources ship. The default replays a pre-generated trajectory dataset —
 * simulated, but generated against a real OSM road network under physical
 * acceleration and speed-limit constraints so the motion is plausible. The
 * alternative is a real public GTFS-Realtime feed. Whichever runs, FR-7.2
 * requires it to disclose its provenance in the product, which the
 * {@code isSubstitute()} / {@code disclosure()} pair carries.
 */
@Configuration
public class IngestionConfig {

    private static final Logger log = LoggerFactory.getLogger(IngestionConfig.class);

    @Bean
    public PositionSource positionSource(FleetProperties properties, ResourceLoader resourceLoader) {
        PositionSource source = switch (properties.positionSource()) {
            case DATASET -> new DatasetPositionSource(loadDataset(properties, resourceLoader));
            case GTFS_REALTIME -> new GtfsRealtimePositionSource(properties.gtfs());
        };
        log.info("Active position source: {} ({}). {}",
                source.id(), source.displayName(), source.disclosure());
        return source;
    }

    private static FleetDataset loadDataset(FleetProperties properties, ResourceLoader loader) {
        String location = properties.dataset().resource();
        Resource resource = loader.getResource(location);
        try {
            FleetDataset dataset = FleetDataset.load(resource);
            log.info("Loaded fleet dataset {} — {} vehicles × {} ticks @ {} ms",
                    location, dataset.vehicleCount(), dataset.tickCount(), dataset.tickMillis());
            return dataset;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not load the fleet trajectory dataset at " + location
                            + ". Generate it with FleetDatasetGenerator, or set "
                            + "tessera.position-source=GTFS_REALTIME.", e);
        }
    }
}
