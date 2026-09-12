package com.tessera.risk.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service configuration, bound from {@code application.yml} and overridable by
 * environment variable so a container needs no rebuilt image (NFR-3).
 */
@ConfigurationProperties(prefix = "tessera")
public class ReportingProperties {

    /** ZooKeeper ensemble HBase registers itself in. */
    private String hbaseZkQuorum = "localhost";
    private String hbaseZkPort = "2181";

    /** Kafka bootstrap servers, for the alert feed. */
    private String kafkaBootstrapServers = "localhost:29092";
    private String alertTopic = "vehicle.alerts";

    /**
     * Alerts retained for a client that connects after they were raised.
     *
     * <p>Bounded on purpose. The buffer exists so a dashboard opened a minute late
     * still shows recent history; it is not a durable store, and letting it grow
     * without limit would be a slow memory leak dressed up as a feature. Kafka is
     * the durable copy.
     */
    private int alertHistory = 200;

    /** Windows returned by the per-vehicle history endpoint. */
    private int historyWindows = 60;

    public String getHbaseZkQuorum() {
        return hbaseZkQuorum;
    }

    public void setHbaseZkQuorum(String hbaseZkQuorum) {
        this.hbaseZkQuorum = hbaseZkQuorum;
    }

    public String getHbaseZkPort() {
        return hbaseZkPort;
    }

    public void setHbaseZkPort(String hbaseZkPort) {
        this.hbaseZkPort = hbaseZkPort;
    }

    public String getKafkaBootstrapServers() {
        return kafkaBootstrapServers;
    }

    public void setKafkaBootstrapServers(String kafkaBootstrapServers) {
        this.kafkaBootstrapServers = kafkaBootstrapServers;
    }

    public String getAlertTopic() {
        return alertTopic;
    }

    public void setAlertTopic(String alertTopic) {
        this.alertTopic = alertTopic;
    }

    public int getAlertHistory() {
        return alertHistory;
    }

    public void setAlertHistory(int alertHistory) {
        this.alertHistory = alertHistory;
    }

    public int getHistoryWindows() {
        return historyWindows;
    }

    public void setHistoryWindows(int historyWindows) {
        this.historyWindows = historyWindows;
    }
}
