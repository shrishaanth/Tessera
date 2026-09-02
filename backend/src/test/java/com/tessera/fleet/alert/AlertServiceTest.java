package com.tessera.fleet.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tessera.fleet.web.ws.LiveWebSocketHandler;

class AlertServiceTest {

    private LiveWebSocketHandler ws;
    private AlertService alerts;

    @BeforeEach
    void setUp() {
        ws = mock(LiveWebSocketHandler.class);
        alerts = new AlertService(ws);
    }

    @Test
    void raisingAnAlertBroadcastsItAndAddsItToTheFeed() {
        Alert a = alerts.raise(Alert.Type.DWELL_EXCEEDED, Alert.Severity.WARNING,
                "CAR-1", "SITE-A", "CAR-1 has been on site too long");

        assertThat(a.id()).startsWith("ALERT-");
        assertThat(a.acknowledged()).isFalse();
        assertThat(alerts.list(false)).containsExactly(a);
        assertThat(alerts.unacknowledgedCount()).isEqualTo(1);
        verify(ws).broadcastAlert(a);
    }

    @Test
    void acknowledgingRemovesItFromTheUnacknowledgedView() {
        Alert a = alerts.raise(Alert.Type.DWELL_EXCEEDED, Alert.Severity.WARNING, "C", "S", "m");
        assertThat(alerts.acknowledge(a.id())).isPresent();

        assertThat(alerts.list(false)).isEmpty();
        assertThat(alerts.list(true)).hasSize(1);
        assertThat(alerts.list(true).get(0).acknowledged()).isTrue();
        assertThat(alerts.unacknowledgedCount()).isZero();
        assertThat(alerts.acknowledge(a.id())).isEmpty(); // already acked
        assertThat(alerts.acknowledge("nope")).isEmpty();
    }

    @Test
    void newestAlertsAreListedFirst() {
        Alert a = alerts.raise(Alert.Type.DWELL_EXCEEDED, Alert.Severity.INFO, "C", "S", "1");
        Alert b = alerts.raise(Alert.Type.DWELL_EXCEEDED, Alert.Severity.INFO, "C", "S", "2");
        assertThat(alerts.list(true)).containsExactly(b, a);
    }
}
