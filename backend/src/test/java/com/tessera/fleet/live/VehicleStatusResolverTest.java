package com.tessera.fleet.live;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tessera.fleet.model.VehicleStatus;
import com.tessera.fleet.support.TestFixtures;

class VehicleStatusResolverTest {

    private final VehicleStatusResolver resolver =
            new VehicleStatusResolver(TestFixtures.fleetProperties()); // offline after 30s

    private static final long NOW = 1_000_000_000_000L;

    @Test
    void freshAndFreeIsAvailable() {
        assertThat(resolver.resolve(NOW - 5_000, NOW, false, false))
                .isEqualTo(VehicleStatus.AVAILABLE);
    }

    @Test
    void freshWithActiveJobIsEnRoute() {
        assertThat(resolver.resolve(NOW - 5_000, NOW, true, false))
                .isEqualTo(VehicleStatus.EN_ROUTE);
    }

    @Test
    void insideGeofenceIsOnSiteEvenWithAJob() {
        assertThat(resolver.resolve(NOW - 5_000, NOW, true, true))
                .isEqualTo(VehicleStatus.ON_SITE);
    }

    @Test
    void staleReportIsOfflineRegardlessOfJob() {
        assertThat(resolver.resolve(NOW - 45_000, NOW, true, true))
                .isEqualTo(VehicleStatus.OFFLINE);
    }

    @Test
    void neverReportedIsOffline() {
        assertThat(resolver.resolve(0, NOW, false, false))
                .isEqualTo(VehicleStatus.OFFLINE);
    }
}
