package com.geotracker.simulator;

import com.geotracker.model.PositionUpdate;

import java.io.*;
import java.net.Socket;
import java.util.List;

public class SimulatorClient {
    private final String host;
    private final int port;
    private Socket socket;
    private DataOutputStream out;
    private volatile boolean running = false;

    public SimulatorClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws IOException {
        socket = new Socket(host, port);
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        running = true;
    }

    public void send(PositionUpdate update) throws IOException {
        if (!running) return;
        out.writeLong(update.vehicleId());
        out.writeDouble(update.x());
        out.writeDouble(update.y());
        out.writeLong(update.timestamp());
        out.flush();
    }

    public void sendBatch(List<PositionUpdate> updates) throws IOException {
        for (PositionUpdate update : updates) {
            send(update);
        }
    }

    public void close() {
        running = false;
        try {
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
