package com.tessera.fleet.geofence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tessera.fleet.alert.AlertService;
import com.tessera.fleet.durable.InMemoryDurableStore;
import com.tessera.fleet.durable.WriteBehindService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.web.ws.LiveWebSocketHandler;

class SiteServiceTest {

    private InMemoryDurableStore store;
    private GeofenceEngine engine;
    private SiteService siteService;

    @BeforeEach
    void setUp() {
        store = new InMemoryDurableStore();
        engine = new GeofenceEngine(20_000, 1_800_000);
        GeofenceService geofenceService = new GeofenceService(engine, store,
                mock(WriteBehindService.class), mock(LiveFleetService.class),
                mock(AlertService.class), mock(LiveWebSocketHandler.class));
        siteService = new SiteService(store, geofenceService);
    }

    @Test
    void createsARadiusSitePersistsItAndLoadsItIntoTheEngine() {
        Site s = siteService.create(new SiteDefinition("Depot", "1 Main St", null,
                42.3560, -71.0635, 150.0, 600));

        assertThat(s.id()).startsWith("SITE-");
        assertThat(s.radiusMeters()).isEqualTo(150.0);
        assertThat(s.dwellAlertSeconds()).isEqualTo(600);
        assertThat(store.loadSites()).hasSize(1);
        assertThat(engine.sites()).extracting(Site::id).containsExactly(s.id());
        assertThat(engine.sites().get(0).contains(42.3560, -71.0635)).isTrue();
    }

    @Test
    void createsAPolygonSite() {
        Site s = siteService.create(new SiteDefinition("Yard", null, List.of(
                List.of(42.3550, -71.0650),
                List.of(42.3550, -71.0620),
                List.of(42.3570, -71.0620),
                List.of(42.3570, -71.0650)), null, null, null, null));
        assertThat(s.geometry().contains(42.3560, -71.0635)).isTrue();
        assertThat(engine.sites()).hasSize(1);
    }

    @Test
    void updatePreservesTheCreationTimestampAndReplacesGeometry() {
        Site original = siteService.create(new SiteDefinition("Depot", null, null,
                42.3560, -71.0635, 100.0, null));
        long createdAt = original.createdAtEpochMs();

        Site updated = siteService.update(original.id(), new SiteDefinition("Depot 2", null, null,
                42.3560, -71.0635, 400.0, null));

        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.createdAtEpochMs()).isEqualTo(createdAt);
        assertThat(updated.name()).isEqualTo("Depot 2");
        assertThat(updated.radiusMeters()).isEqualTo(400.0);
        assertThat(store.loadSites()).hasSize(1);
    }

    @Test
    void deleteRemovesFromStoreAndEngine() {
        Site s = siteService.create(new SiteDefinition("Depot", null, null,
                42.3560, -71.0635, 100.0, null));
        siteService.delete(s.id());
        assertThat(store.loadSites()).isEmpty();
        assertThat(engine.sites()).isEmpty();
    }

    @Test
    void rejectsInvalidDefinitions() {
        assertThatThrownBy(() -> siteService.create(new SiteDefinition("", null, null,
                42.0, -71.0, 100.0, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> siteService.create(new SiteDefinition("No shape", null,
                null, null, null, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> siteService.update("missing", new SiteDefinition("x", null, null,
                42.0, -71.0, 100.0, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> siteService.delete("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
