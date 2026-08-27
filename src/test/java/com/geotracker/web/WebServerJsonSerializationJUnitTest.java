package com.geotracker.web;

import com.geotracker.model.Position;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

public class WebServerJsonSerializationJUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializePositionsProducesExpectedJsonShape() throws Exception {
        var pos = new Position(-122.331, 47.648, 123456789L);
        Map<String, Object> map = WebServer.positionToMap(42, pos);

        List<Map<String, Object>> list = List.of(map);
        String json = mapper.writeValueAsString(list);

        String expected = "[{\"vehicleId\":42,\"x\":-122.331,\"y\":47.648,\"timestamp\":123456789}]";
        assertEquals(expected, json);
    }

    @Test
    void serializeMultiplePositions() throws Exception {
        var p1 = new Position(-122.331, 47.648, 1000);
        var p2 = new Position(-122.330, 47.649, 2000);

        var m1 = WebServer.positionToMap(1, p1);
        var m2 = WebServer.positionToMap(2, p2);

        String json = mapper.writeValueAsString(List.of(m1, m2));
        assertTrue(json.contains("\"vehicleId\":1"));
        assertTrue(json.contains("\"vehicleId\":2"));
        assertTrue(json.contains("\"x\":-122.331"));
        assertTrue(json.contains("\"y\":47.649"));
    }
}
