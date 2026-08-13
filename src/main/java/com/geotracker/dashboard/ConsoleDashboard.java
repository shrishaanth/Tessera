package com.geotracker.dashboard;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;

import java.util.List;

public class ConsoleDashboard implements Dashboard {
    private final CowQuadtree quadtree;
    private final HamtIndex hamt;
    private final BoundingBox mapBounds;
    private volatile boolean running = true;
    private volatile long frameCount = 0;
    private volatile long lastFpsTime = System.currentTimeMillis();
    private volatile int fps = 0;

    public ConsoleDashboard(CowQuadtree quadtree, HamtIndex hamt, BoundingBox mapBounds) {
        this.quadtree = quadtree;
        this.hamt = hamt;
        this.mapBounds = mapBounds;
    }

    @Override
    public void start() {
        System.out.println("Console Dashboard started. Press Ctrl+C to stop.");
        while (running) {
            render();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    private void render() {
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            fps = (int) (frameCount * 1000 / (now - lastFpsTime));
            frameCount = 0;
            lastFpsTime = now;
        }

        List<Long> allVehicles = quadtree.rangeQuery(mapBounds);
        int visibleCount = allVehicles.size();

        System.out.printf("\033[H\033[2J");
        System.out.println("=== Tessera Dashboard ===");
        System.out.println("FPS: " + fps);
        System.out.println("Total Vehicles: " + visibleCount);
        System.out.println("Map Bounds: (" + mapBounds.minX() + ", " + mapBounds.minY() + ") to (" + mapBounds.maxX() + ", " + mapBounds.maxY() + ")");
        System.out.println("=========================");

        int sampleSize = Math.min(10, allVehicles.size());
        for (int i = 0; i < sampleSize; i++) {
            long vehicleId = allVehicles.get(i);
            Position pos = hamt.get(vehicleId);
            if (pos != null) {
                System.out.printf("Vehicle %d: (%.1f, %.1f)%n", vehicleId, pos.x(), pos.y());
            }
        }
        if (visibleCount > sampleSize) {
            System.out.println("... and " + (visibleCount - sampleSize) + " more");
        }
    }
}
