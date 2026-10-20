package com.geotracker.fx;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.service.ApplicationService.VehicleInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The map payloads must put latitude first (Leaflet order) although the backend stores x = longitude. */
class MapPayloadsTest {

    @Test
    void vehiclePayloadIsLatLngOrdered() {
        VehicleInfo v = new VehicleInfo(7, -122.3331234, 47.6481234, 0, 30.0, 92.4, "Active", "generated");
        assertEquals("{\"v\":[7,47.6481234,-122.3331234,92,0,0]}", MapPayloads.vehicles(List.of(v)));
    }

    @Test
    void vehiclePayloadEncodesUnknownHeadingStatusAndSource() {
        VehicleInfo v = new VehicleInfo(3, -122.0, 47.0, 0, 0.0, -1.0, "Stale", "csv");
        assertEquals("{\"v\":[3,47.0000000,-122.0000000,-1,1,1]}", MapPayloads.vehicles(List.of(v)));
        assertEquals(MapPayloads.STATUS_PAUSED, MapPayloads.statusCode("Paused"));
        assertEquals(MapPayloads.STATUS_IDLE, MapPayloads.statusCode("Idle"));
    }

    @Test
    void roadSegmentsAreConvertedToLatLng() {
        String json = MapPayloads.roadNetwork(List.of(new double[]{-122.1, 47.1, -122.2, 47.2}));
        assertEquals("[[[47.1000000,-122.1000000],[47.2000000,-122.2000000]]]", json);
    }

    @Test
    void boundsAreSouthWestNorthEast() {
        assertEquals("[47.6455000,-122.3364000,47.6509000,-122.3304000]",
                MapPayloads.bounds(new BoundingBox(-122.3364, 47.6455, -122.3304, 47.6509)));
    }

    @Test
    void polygonsAreConvertedToLatLng() {
        assertEquals("[[47.0000000,-122.0000000],[48.0000000,-123.0000000]]",
                MapPayloads.latLngs(List.of(new Position(-122, 47, 0), new Position(-123, 48, 0))));
    }

    @Test
    void coordinatesUseFixedNotation() {
        StringBuilder sb = new StringBuilder();
        MapPayloads.coord(sb, -0.00000004);
        assertEquals("0.0000000", sb.toString());
        sb.setLength(0);
        MapPayloads.coord(sb, -0.5);
        assertEquals("-0.5000000", sb.toString());
        sb.setLength(0);
        MapPayloads.coord(sb, 1e-9);
        assertEquals("0.0000000", sb.toString());
    }

    @Test
    void userTextIsEscapedForJsonAndJavaScript() {
        String name = "Depot \"A\" </script>\n'quote' \\  ";
        String json = MapPayloads.jsonString(name);
        assertFalse(json.contains("</"), "Must not be able to close a script element");
        assertFalse(json.contains("\n"));
        String arg = MapPayloads.jsArgument("{\"name\":" + json + "}");
        assertTrue(arg.startsWith("'") && arg.endsWith("'"));
        assertFalse(arg.substring(1, arg.length() - 1).matches(".*(?<!\\\\)'.*"), "Unescaped single quote: " + arg);
        assertFalse(arg.contains(" "));
    }
}
