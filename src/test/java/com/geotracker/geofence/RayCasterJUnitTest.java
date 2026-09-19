package com.geotracker.geofence;

import com.geotracker.geofence.GeofenceEngine.Zone;
import com.geotracker.model.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class RayCasterJUnitTest {

    @Test
    void insideSquare() {
        var polygon = List.of(
                new Position(0, 0, 0),
                new Position(10, 0, 0),
                new Position(10, 10, 0),
                new Position(0, 10, 0)
        );
        assertTrue(RayCaster.contains(new Position(5, 5, 0), polygon));
    }

    @Test
    void outsideSquare() {
        var polygon = List.of(
                new Position(0, 0, 0),
                new Position(10, 0, 0),
                new Position(10, 10, 0),
                new Position(0, 10, 0)
        );
        assertFalse(RayCaster.contains(new Position(15, 5, 0), polygon));
    }

    @Test
    void onEdge() {
        var polygon = List.of(
                new Position(0, 0, 0),
                new Position(10, 0, 0),
                new Position(10, 10, 0),
                new Position(0, 10, 0)
        );
        assertTrue(RayCaster.contains(new Position(5, 0, 0), polygon));
    }

    @Test
    void complexPolygon() {
        var polygon = List.of(
                new Position(0, 0, 0),
                new Position(10, 5, 0),
                new Position(0, 10, 0)
        );
        assertTrue(RayCaster.contains(new Position(2, 5, 0), polygon));
        assertFalse(RayCaster.contains(new Position(10, 10, 0), polygon));
    }
}
