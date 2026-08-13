package com.geotracker.ingestion;

import com.geotracker.model.PositionUpdate;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IngestionServer {
    private final int port;
    private final ShardRouter shardRouter;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final ExecutorService executor;

    public IngestionServer(int port, ShardRouter shardRouter) {
        this.port = port;
        this.shardRouter = shardRouter;
        this.executor = Executors.newCachedThreadPool();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("IngestionServer listening on port " + port);
        while (running) {
            Socket client = serverSocket.accept();
            executor.submit(() -> handleClient(client));
        }
    }

    private void handleClient(Socket client) {
        try (InputStream in = client.getInputStream()) {
            while (running && !client.isClosed()) {
                try {
                    PositionUpdate update = PositionDecoder.decode(in);
                    shardRouter.route(update);
                } catch (IOException e) {
                    break;
                }
            }
        } catch (IOException e) {
            // client disconnected
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            // ignore
        }
        executor.shutdown();
    }
}
