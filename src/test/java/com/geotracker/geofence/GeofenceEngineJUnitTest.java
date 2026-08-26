package com.geotracker.geofence;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.ZoneEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class GeofenceEngineJUnitTest {

    @Test
    void detectsEnterAndExit() {
        BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
        CowQuadtree qt = new CowQuadtree(bounds);
        HamtIndex hamt = new HamtIndex();

        qt.insert(1, 450, 450);
        hamt.put(1, new Position(450, 450, 1000));
        qt.publish();
        hamt.publish();

        var zone = new GeofenceEngine.Zone("center", List.of(
                new Position(400, 400, 0),
                new Position(600, 400, 0),
                new Position(600, 600, 0),
                new Position(400, 600, 0)
        ), new BoundingBox(400, 400, 600, 600));

        GeofenceEngine engine = new GeofenceEngine(new CowQuadtree[]{qt}, new HamtIndex[]{hamt}, List.of(zone));

        var events = engine.check();
        assertTrue(events.stream().anyMatch(e -> e.vehicleId() == 1 && e.type() == ZoneEvent.EventType.ENTER));

        hamt.put(1, new Position(700, 700, 2000));
        qt.update(1, 450, 450, 700, 700);
        qt.publish();
        hamt.publish();

        events = engine.check();
        assertTrue(events.stream().anyMatch(e -> e.vehicleId() == 1 && e.type() == ZoneEvent.EventType.EXIT));
    }
}
