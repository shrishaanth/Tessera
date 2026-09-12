package com.tessera.risk.reporting.alert;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.model.RiskAlert;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.reporting.ReportingProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the bounded alert buffer.
 *
 * <p>The bound is the point. An unbounded "recent alerts" list is a memory leak
 * that looks like a feature and only shows itself after the service has been up for
 * days, which is exactly when nobody is watching.
 */
class AlertHistoryTest {

    @Test
    @DisplayName("the buffer never grows past its capacity")
    void capacityIsEnforced() {
        AlertHistory history = new AlertHistory(properties(5));
        for (int i = 0; i < 50; i++) {
            history.add(alert("VEH-%03d".formatted(i), i));
        }
        assertThat(history.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("the oldest alerts are the ones dropped")
    void oldestAreEvicted() {
        AlertHistory history = new AlertHistory(properties(3));
        history.add(alert("VEH-001", 1));
        history.add(alert("VEH-002", 2));
        history.add(alert("VEH-003", 3));
        history.add(alert("VEH-004", 4));

        assertThat(history.recent(10)).extracting(RiskAlert::vehicleId)
                .containsExactly("VEH-004", "VEH-003", "VEH-002");
    }

    @Test
    @DisplayName("alerts come back newest first")
    void newestFirst() {
        AlertHistory history = new AlertHistory(properties(10));
        history.add(alert("VEH-001", 1));
        history.add(alert("VEH-002", 2));

        // So a client rendering a feed does not have to reverse it, and so the most
        // recent alert is the one seen when the list is truncated.
        assertThat(history.recent(10)).extracting(RiskAlert::vehicleId)
                .containsExactly("VEH-002", "VEH-001");
    }

    @Test
    @DisplayName("asking for more than exists returns what there is")
    void limitBeyondSizeIsSafe() {
        AlertHistory history = new AlertHistory(properties(10));
        history.add(alert("VEH-001", 1));
        assertThat(history.recent(100)).hasSize(1);
    }

    @Test
    @DisplayName("an empty buffer returns an empty list, not null")
    void emptyBufferIsEmptyList() {
        assertThat(new AlertHistory(properties(10)).recent(10)).isEmpty();
    }

    @Test
    @DisplayName("a nonsensical capacity still leaves a usable buffer")
    void zeroCapacityIsClampedToOne() {
        AlertHistory history = new AlertHistory(properties(0));
        history.add(alert("VEH-001", 1));
        assertThat(history.recent(10)).hasSize(1);
    }

    private static ReportingProperties properties(int capacity) {
        ReportingProperties properties = new ReportingProperties();
        properties.setAlertHistory(capacity);
        return properties;
    }

    private static RiskAlert alert(String vehicleId, long at) {
        return new RiskAlert(vehicleId, "DRV-001", RiskTier.RISKY, at, at + 300_000L,
                78.0, 12, 3, 1, 300, "test", at);
    }
}
