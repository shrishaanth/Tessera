package com.geotracker.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests vehicle count scaling at 20, 100, 1000, and 10000.
 * Measures resource usage to determine practical limits.
 */
class VehicleScaleTest {

    private ApplicationService service;

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
    }

    @Test
    @Timeout(15)
    void scale20Vehicles() throws InterruptedException {
        service = new ApplicationService();
        service.setVehicleCount(20);
        service.startSimulation();
        Thread.sleep(3000);
        int active = service.getActiveVehicleCount();
        assertTrue(active > 0, "Expected active vehicles, got " + active);
        long updates = service.getTotalUpdates();
        assertTrue(updates > 0, "Expected updates, got " + updates);
        System.out.println("[SCALE 20] Active: " + active + ", Updates: " + updates +
                ", Rate: " + String.format("%.0f/s", service.getMeasuredUpdateRate()) +
                ", Heap: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024) + "MB");
        service.stopSimulation();
    }

    @Test
    @Timeout(15)
    void scale100Vehicles() throws InterruptedException {
        service = new ApplicationService();
        service.setVehicleCount(100);
        service.startSimulation();
        Thread.sleep(3000);
        int active = service.getActiveVehicleCount();
        assertTrue(active > 20, "Expected >20 active vehicles, got " + active);
        long updates = service.getTotalUpdates();
        assertTrue(updates > 0);
        System.out.println("[SCALE 100] Active: " + active + ", Updates: " + updates +
                ", Rate: " + String.format("%.0f/s", service.getMeasuredUpdateRate()) +
                ", Heap: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024) + "MB");
        service.stopSimulation();
    }

    @Test
    @Timeout(20)
    void scale1000Vehicles() throws InterruptedException {
        service = new ApplicationService();
        service.setVehicleCount(1000);
        service.startSimulation();
        Thread.sleep(4000);
        int active = service.getActiveVehicleCount();
        assertTrue(active > 100, "Expected >100 active vehicles, got " + active);
        long updates = service.getTotalUpdates();
        assertTrue(updates > 0);
        System.out.println("[SCALE 1000] Active: " + active + ", Updates: " + updates +
                ", Rate: " + String.format("%.0f/s", service.getMeasuredUpdateRate()) +
                ", Heap: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024) + "MB");
        service.stopSimulation();
    }

    @Test
    @Timeout(30)
    void scale10000Vehicles() throws InterruptedException {
        service = new ApplicationService();
        service.setVehicleCount(10000);
        service.startSimulation();
        Thread.sleep(5000);
        int active = service.getActiveVehicleCount();
        assertTrue(active > 100, "Expected >100 active vehicles, got " + active);
        long updates = service.getTotalUpdates();
        assertTrue(updates > 0);
        System.out.println("[SCALE 10000] Active: " + active + ", Updates: " + updates +
                ", Rate: " + String.format("%.0f/s", service.getMeasuredUpdateRate()) +
                ", Heap: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024) + "MB");
        service.stopSimulation();
    }
}
