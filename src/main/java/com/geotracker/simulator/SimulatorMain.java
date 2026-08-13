package com.geotracker;

import com.geotracker.routing.RoadGraph;
import com.geotracker.simulator.SimulatorClient;
import com.geotracker.simulator.VehicleSimulator;
import com.geotracker.util.Config;

public class SimulatorMain {
    public static void main(String[] args) throws Exception {
        int vehicleCount = Config.VEHICLE_COUNT;
        double rateHz = Config.UPDATE_RATE_HZ;
        long seed = Config.SEED;

        RoadGraph graph = RoadGraph.builder()
                .addGrid(20, 20, 50.0)
                .build();

        VehicleSimulator simulator = new VehicleSimulator(graph, vehicleCount, 100.0, 1.0 / rateHz, seed);

        SimulatorClient client = new SimulatorClient("localhost", Config.PORT);
        client.connect();

        Runtime.getRuntime().addShutdownHook(new Thread(client::close));

        System.out.println("Simulator started: " + vehicleCount + " vehicles at " + rateHz + " Hz");

        long tickIntervalMs = (long) (1000.0 / rateHz);
        long nextTick = System.currentTimeMillis();

        while (true) {
            long now = System.currentTimeMillis();
            if (now < nextTick) {
                Thread.sleep(nextTick - now);
            }
            nextTick += tickIntervalMs;

            var updates = simulator.tick();
            client.sendBatch(updates);
        }
    }
}
