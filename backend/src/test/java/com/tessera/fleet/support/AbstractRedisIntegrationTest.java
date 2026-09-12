package com.tessera.fleet.support;

import java.io.IOException;
import java.net.ServerSocket;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import redis.embedded.RedisServer;

/**
 * Base class for integration tests that need the live layer's Redis instance.
 *
 * <p>Starts a single real {@code redis-server} for the whole test JVM from a
 * bundled native binary (no Docker required) and points Spring Data Redis at it.
 * One shared instance keeps the Spring context cache warm across IT classes and
 * avoids churn from repeatedly starting/stopping the native process. The
 * production live layer targets Redis 7 (see {@code docker-compose.yml}); this
 * exercises the same GEOADD / GEORADIUS / hash operations against a real server.
 */
public abstract class AbstractRedisIntegrationTest {

    private static volatile RedisServer server;
    private static int port;

    private static synchronized void ensureStarted() {
        if (server != null) {
            return;
        }
        try {
            port = findFreePort();
            RedisServer started = RedisServer.newRedisServer()
                    .port(port)
                    .setting("maxmemory 128mb")
                    .setting("save \"\"")
                    .build();
            started.start();
            server = started;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    started.stop();
                } catch (Exception ignored) {
                    // best effort on JVM shutdown
                }
            }));
        } catch (IOException e) {
            throw new IllegalStateException("Could not start embedded Redis", e);
        }
    }

    @DynamicPropertySource
    static void integrationTestProperties(DynamicPropertyRegistry registry) {
        ensureStarted();
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> port);
        // Integration tests assert on exact fleet contents, so the live feed must
        // produce nothing: select the GTFS source and point it at a dead local
        // address. Its one startup poll fails fast and yields an empty batch, so
        // vehicles only exist where a test injects them. (This also guarantees no
        // test ever reaches the real MBTA feed.)
        registry.add("tessera.position-source", () -> "GTFS_REALTIME");
        registry.add("tessera.gtfs.feed-url", () -> "http://127.0.0.1:9/disabled-in-tests");
        registry.add("tessera.gtfs.poll-millis", () -> "3600000");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
