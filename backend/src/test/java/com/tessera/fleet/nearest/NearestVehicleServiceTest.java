package com.tessera.fleet.nearest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tessera.fleet.live.GeoCandidate;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.NearestVehicle;
import com.tessera.fleet.model.Vehicle;
import com.tessera.fleet.model.VehicleStatus;
import com.tessera.fleet.routing.GeoMath;
import com.tessera.fleet.routing.TravelTimeService;
import com.tessera.fleet.support.TestFixtures;

class NearestVehicleServiceTest {

    private static final double JOB_LAT = 42.3601;
    private static final double JOB_LON = -71.0589;

    private final TravelTimeService travelTime =
            new TravelTimeService(TestFixtures.realRoadGraph());

    private static Vehicle vehicle(String id, VehicleStatus status, double lat, double lon) {
        return new Vehicle(id, id + "-driver", status, lat, lon,
                0.0, 20.0, System.currentTimeMillis(), null);
    }

    private static GeoCandidate candidate(String id, double lat, double lon) {
        return new GeoCandidate(id, lat, lon,
                GeoMath.haversineMeters(JOB_LAT, JOB_LON, lat, lon));
    }

    @Test
    void ranksAvailableVehiclesByRoadNetworkTravelTimeAndExcludesBusyOnes() {
        LiveFleetService live = mock(LiveFleetService.class);

        Vehicle near = vehicle("V-NEAR", VehicleStatus.AVAILABLE, 42.3606, -71.0585);
        Vehicle far = vehicle("V-FAR", VehicleStatus.AVAILABLE, 42.3520, -71.0720);
        Vehicle busy = vehicle("V-BUSY", VehicleStatus.EN_ROUTE, 42.3602, -71.0590);

        when(live.searchNearby(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(
                        candidate("V-BUSY", busy.latitude(), busy.longitude()),
                        candidate("V-NEAR", near.latitude(), near.longitude()),
                        candidate("V-FAR", far.latitude(), far.longitude())));
        lenient().when(live.getVehicle("V-NEAR")).thenReturn(near);
        lenient().when(live.getVehicle("V-FAR")).thenReturn(far);
        lenient().when(live.getVehicle("V-BUSY")).thenReturn(busy);

        NearestVehicleService service =
                new NearestVehicleService(live, travelTime, TestFixtures.fleetProperties());

        List<NearestVehicle> ranked = service.nearestAvailable(JOB_LAT, JOB_LON, 5);

        assertThat(ranked).extracting(NearestVehicle::vehicleId)
                .containsExactly("V-NEAR", "V-FAR");
        assertThat(ranked.get(0).travelSeconds())
                .isLessThan(ranked.get(1).travelSeconds());
        assertThat(ranked.get(0).travelSeconds()).isGreaterThan(0.0);
    }

    @Test
    void honoursTheShortlistLimit() {
        LiveFleetService live = mock(LiveFleetService.class);
        when(live.searchNearby(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(
                        candidate("A", 42.3606, -71.0585),
                        candidate("B", 42.3596, -71.0600),
                        candidate("C", 42.3585, -71.0575),
                        candidate("D", 42.3612, -71.0560)));
        for (String id : List.of("A", "B", "C", "D")) {
            lenient().when(live.getVehicle(id))
                    .thenReturn(vehicle(id, VehicleStatus.AVAILABLE,
                            42.36 + Math.random() * 0.001, -71.058 - Math.random() * 0.001));
        }
        NearestVehicleService service =
                new NearestVehicleService(live, travelTime, TestFixtures.fleetProperties());

        assertThat(service.nearestAvailable(JOB_LAT, JOB_LON, 2)).hasSize(2);
    }

    @Test
    void returnsEmptyWhenNoAvailableVehiclesInRange() {
        LiveFleetService live = mock(LiveFleetService.class);
        when(live.searchNearby(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of());
        NearestVehicleService service =
                new NearestVehicleService(live, travelTime, TestFixtures.fleetProperties());
        assertThat(service.nearestAvailable(JOB_LAT, JOB_LON, 5)).isEmpty();
    }
}
