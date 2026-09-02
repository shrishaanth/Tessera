package com.tessera.fleet.durable;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces the durable layer's state without ever failing the app's liveness:
 * the live path keeps working when the database is unreachable (NFR-3), so a
 * degraded durable layer reports {@code OUT_OF_SERVICE}, not {@code DOWN}.
 */
@Component("durableLayer")
public class DurableHealthIndicator implements HealthIndicator {

    private final WriteBehindService writeBehind;

    public DurableHealthIndicator(WriteBehindService writeBehind) {
        this.writeBehind = writeBehind;
    }

    @Override
    public Health health() {
        WriteBehindService.Stats s = writeBehind.stats();
        Health.Builder builder = s.healthy() ? Health.up() : Health.status("OUT_OF_SERVICE");
        return builder
                .withDetail("writtenPositions", s.writtenPositions())
                .withDetail("queueDepth", s.queueDepth())
                .withDetail("droppedFull", s.droppedFull())
                .withDetail("droppedError", s.droppedError())
                .withDetail("writeFailures", s.writeFailures())
                .withDetail("lastError", s.lastError() == null ? "" : s.lastError())
                .build();
    }
}
