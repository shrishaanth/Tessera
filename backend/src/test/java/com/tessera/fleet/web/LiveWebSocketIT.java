package com.tessera.fleet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.tessera.fleet.live.LiveBroadcastService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.support.AbstractRedisIntegrationTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tessera.simulator.vehicle-count=0",
        "tessera.ingest-poll-millis=3600000",
        "tessera.broadcast-millis=3600000"
})
class LiveWebSocketIT extends AbstractRedisIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    LiveFleetService liveFleet;

    @Autowired
    LiveBroadcastService broadcastService;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void seed() {
        liveFleet.flushAll();
        liveFleet.applyReport(new PositionReport("WS-1", "Nia", 42.3601, -71.0589,
                0, 20, System.currentTimeMillis()));
    }

    private String sessionCookie() {
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/auth/login",
                Map.of("username", "dispatch", "password", "dispatch"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0];
    }

    @Test
    void authenticatedClientReceivesAFleetSnapshotWithinTwoSeconds() throws Exception {
        CompletableFuture<String> firstMessage = new CompletableFuture<>();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie());

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                firstMessage.complete(message.getPayload());
            }
        }, headers, URI.create("ws://localhost:" + port + "/ws/live")).get(5, TimeUnit.SECONDS);

        // The scheduler is disabled in this test; drive one broadcast explicitly.
        broadcastService.tick();

        String payload = firstMessage.get(2, TimeUnit.SECONDS);
        Map<String, Object> frame = mapper.readValue(payload, Map.class);
        assertThat(frame.get("type")).isEqualTo("fleet");
        assertThat((List<?>) frame.get("vehicles")).isNotEmpty();

        session.close(CloseStatus.NORMAL);
    }

    @Test
    void unauthenticatedHandshakeIsRejected() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        assertThatThrownBy(() -> client.execute(new TextWebSocketHandler() { },
                new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/live"))
                .get(5, TimeUnit.SECONDS))
                .hasMessageContaining("401");
    }
}
