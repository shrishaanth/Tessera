package com.tessera.fleet.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;

import org.junit.jupiter.api.Test;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.model.PositionReport;

class GtfsRealtimePositionSourceTest {

    private GtfsRealtimePositionSource source() {
        return new GtfsRealtimePositionSource(new FleetProperties.Gtfs(
                "https://example.test/vehiclepositions", null, null, 15000L, "Test Transit"));
    }

    private static FeedEntity vehicleEntity(String id, double lat, double lon,
                                            float speedMps, long tsSec) {
        return FeedEntity.newBuilder()
                .setId(id)
                .setVehicle(VehiclePosition.newBuilder()
                        .setVehicle(VehicleDescriptor.newBuilder().setId(id).setLabel("Bus " + id))
                        .setPosition(Position.newBuilder()
                                .setLatitude((float) lat)
                                .setLongitude((float) lon)
                                .setBearing(90f)
                                .setSpeed(speedMps))
                        .setTimestamp(tsSec))
                .build();
    }

    @Test
    void parsesVehiclePositionsAndConvertsUnits() {
        FeedMessage feed = FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
                .addEntity(vehicleEntity("100", 42.3601, -71.0589, 10f, 1_700_000_000L))
                .build();

        List<PositionReport> reports = source().parse(feed, 999L);

        assertThat(reports).hasSize(1);
        PositionReport r = reports.get(0);
        assertThat(r.vehicleId()).isEqualTo("GTFS-100");
        assertThat(r.driverName()).isEqualTo("Bus 100");
        assertThat(r.latitude()).isCloseTo(42.3601, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(r.speedKph()).isCloseTo(36.0, org.assertj.core.data.Offset.offset(0.5)); // 10 m/s
        assertThat(r.epochMillis()).isEqualTo(1_700_000_000L * 1000L);
    }

    @Test
    void skipsEntitiesWithoutPositionsOrAtNullIsland() {
        FeedMessage feed = FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
                .addEntity(FeedEntity.newBuilder().setId("no-vehicle").build())
                .addEntity(vehicleEntity("zero", 0.0, 0.0, 0f, 1_700_000_000L))
                .addEntity(vehicleEntity("ok", 42.36, -71.06, 5f, 1_700_000_050L))
                .build();

        List<PositionReport> reports = source().parse(feed, 999L);

        assertThat(reports).extracting(PositionReport::vehicleId).containsExactly("GTFS-ok");
    }

    @Test
    void isDisclosedAsASubstituteDataSource() {
        GtfsRealtimePositionSource s = source();
        assertThat(s.isSubstitute()).isTrue();
        assertThat(s.disclosure())
                .contains("TRANSIT VEHICLES")
                .contains("Test Transit");
    }
}
