package com.tessera.risk.reporting.alert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tessera.risk.common.model.RiskAlert;
import com.tessera.risk.reporting.ReportingProperties;

/**
 * The most recent alerts, in memory and bounded.
 *
 * <h2>Why this is not read from HBase</h2>
 * Alerts are notifications, not state. An operator needs them within seconds of
 * being raised, and polling a table for rows that might appear shortly is the wrong
 * shape for that — it is either too slow or too expensive. They come off Kafka and
 * go straight out to clients.
 *
 * <p>This buffer exists only so a dashboard opened a minute late still shows what
 * just happened. It is explicitly not durable: Kafka holds the durable copy, and a
 * restart of this service is expected to lose the buffer. Bounding it is what keeps
 * that honest — an unbounded "history" would be a slow memory leak presented as a
 * feature.
 */
@Component
public class AlertHistory {

    private final Deque<RiskAlert> recent = new ArrayDeque<>();
    private final int capacity;

    public AlertHistory(ReportingProperties properties) {
        this.capacity = Math.max(1, properties.getAlertHistory());
    }

    /** Newest first, so a client rendering a feed does not have to reverse it. */
    public synchronized List<RiskAlert> recent(int limit) {
        List<RiskAlert> out = new ArrayList<>(Math.min(limit, recent.size()));
        for (RiskAlert alert : recent) {
            if (out.size() >= limit) {
                break;
            }
            out.add(alert);
        }
        return out;
    }

    public synchronized void add(RiskAlert alert) {
        recent.addFirst(alert);
        while (recent.size() > capacity) {
            recent.removeLast();
        }
    }

    public synchronized int size() {
        return recent.size();
    }
}
