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
    void freshReportOutsideAnySiteIsActive() {
        assertThat(resolver.resolve(NOW - 5_000, NOW, false))
                .isEqualTo(VehicleStatus.ACTIVE);
    }

    @Test
    void insideGeofenceIsOnSite() {
        assertThat(resolver.resolve(NOW - 5_000, NOW, true))
                .isEqualTo(VehicleStatus.ON_SITE);
    }

    @Test
    void staleReportIsOfflineEvenWhenInsideASite() {
        assertThat(resolver.resolve(NOW - 45_000, NOW, true))
                .isEqualTo(VehicleStatus.OFFLINE);
    }

    @Test
    void neverReportedIsOffline() {
        assertThat(resolver.resolve(0, NOW, false))
                .isEqualTo(VehicleStatus.OFFLINE);
    }
}
