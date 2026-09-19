package com.geotracker.dashboard;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ConsoleDashboardJUnitTest {

    @Test
    void consoleDashboardStartsAndStops() throws Exception {
        BoundingBox bounds = new BoundingBox(0, 0, 1000, 1000);
        CowQuadtree quadtree = new CowQuadtree(bounds);
        HamtIndex hamt = new HamtIndex();

        quadtree.insert(1, 100, 100);
        quadtree.insert(2, 200, 200);
        quadtree.publish();
        hamt.put(1, new Position(100, 100, 1000));
        hamt.put(2, new Position(200, 200, 2000));

        ConsoleDashboard dashboard = new ConsoleDashboard(quadtree, hamt, bounds);

        Thread thread = new Thread(dashboard::start);
        thread.start();
        Thread.sleep(200);
        dashboard.stop();
        thread.join(1000);

        assertFalse(thread.isAlive(), "ConsoleDashboard thread did not stop");
    }
}
