package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

public class SimulatorBatchJUnitTest {

    @Test
    void sendBatchWritesAllUpdates() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        SimulatorClient client = new SimulatorClient("localhost", 0);
        java.lang.reflect.Field outField = SimulatorClient.class.getDeclaredField("out");
        outField.setAccessible(true);
        outField.set(client, dos);

        java.lang.reflect.Field runningField = SimulatorClient.class.getDeclaredField("running");
        runningField.setAccessible(true);
        runningField.setBoolean(client, true);

        List<PositionUpdate> updates = List.of(
                new PositionUpdate(1, 10, 20, 1000),
                new PositionUpdate(2, 30, 40, 2000),
                new PositionUpdate(3, 50, 60, 3000)
        );
        client.sendBatch(updates);
        dos.flush();

        byte[] data = baos.toByteArray();
        assertEquals(96, data.length);
    }
}
