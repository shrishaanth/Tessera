package com.geotracker.geofence;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.ZoneEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GeofenceEngineStateTest {

    private static final BoundingBox WORLD = new BoundingBox(0, 0, 100, 100);

    private static GeofenceEngine engineWith(CowQuadtree qt, HamtIndex hamt) {
        GeofenceEngine.Zone zone = new GeofenceEngine.Zone("square", List.of(
                new Position(10, 10, 0), new Position(20, 10, 0), new Position(20, 20, 0), new Position(10, 20, 0)),
                new BoundingBox(10, 10, 20, 20));
        return new GeofenceEngine(new CowQuadtree[]{qt}, new HamtIndex[]{hamt}, List.of(zone));
    }

    private static void place(CowQuadtree qt, HamtIndex hamt, long id, double x, double y) {
        Position old = hamt.get(id);
        if (old == null) qt.insert(id, x, y); else qt.update(id, old.x(), old.y(), x, y);
        hamt.put(id, new Position(x, y, System.currentTimeMillis()));
        qt.publish();
        hamt.publish();
    }

    @Test
    void occupancyTracksVehiclesInside() {
        CowQuadtree qt = new CowQuadtree(WORLD);
        HamtIndex hamt = new HamtIndex();
        GeofenceEngine engine = engineWith(qt, hamt);
        place(qt, hamt, 1, 15, 15);
        place(qt, hamt, 2, 50, 50);
        List<ZoneEvent> events = engine.check();
        assertEquals(1, events.size());
        assertEquals(ZoneEvent.EventType.ENTER, events.get(0).type());
        assertEquals(1, engine.getOccupancy("square"));
        assertEquals(1, engine.getBuiltInZones().size());
    }

    @Test
    void resetStatePreventsSpuriousExitEventsAfterARestart() {
        CowQuadtree qt = new CowQuadtree(WORLD);
        HamtIndex hamt = new HamtIndex();
        GeofenceEngine engine = engineWith(qt, hamt);
        place(qt, hamt, 1, 15, 15);
        engine.check();
        assertEquals(1, engine.getOccupancy("square"));

        // New run: the index is cleared and the engine reset; the vehicle reappears elsewhere.
        engine.resetState();
        assertEquals(0, engine.getOccupancy("square"));
        place(qt, hamt, 1, 50, 50);
        assertTrue(engine.check().isEmpty(), "No EXIT should be reported for a vehicle from the previous run");
    }

    @Test
    void userZoneUsesAllVehiclesWhenNoneAreListed() {
        CowQuadtree qt = new CowQuadtree(WORLD);
        HamtIndex hamt = new HamtIndex();
        GeofenceEngine engine = engineWith(qt, hamt);
        String id = engine.createZone("Depot", List.of(
                new Position(60, 60, 0), new Position(70, 60, 0), new Position(65, 70, 0)), Set.of(), true, true);
        place(qt, hamt, 9, 65, 63);
        List<ZoneEvent> events = engine.check();
        assertTrue(events.stream().anyMatch(e -> e.zoneId().equals(id) && e.type() == ZoneEvent.EventType.ENTER));
        assertEquals(1, engine.getOccupancy(id));
    }
}
