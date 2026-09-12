package com.tessera.risk.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One telemetry reading from one vehicle — the single high-volume event in the
 * system, published to the {@code vehicle.telemetry} topic once per vehicle per
 * second as a JSON document.
 *
 * <p>Deliberately flat and primitive-typed: it crosses a Kafka topic and is read
 * back by Spark, whose schema inference and columnar encoding both work best on a
 * flat structure.
 *
 * @param vehicleId  stable vehicle identifier; also the Kafka partition key, so
 *                   all readings for a vehicle stay ordered within a partition
 * @param driverId   driver currently assigned to the vehicle
 * @param riskTier   the driver's standing profile (a model feature, not a label)
 * @param lat        WGS-84 latitude, interpolated along a real road segment
 * @param lon        WGS-84 longitude
 * @param speedKph   instantaneous speed
 * @param headingDeg bearing along the current segment, degrees clockwise from north
 * @param segmentId  road segment currently occupied
 * @param speedLimitKph posted limit of that segment, denormalised so downstream
 *                   consumers need not join against the road network
 * @param eventType  classification of this reading
 * @param severity   normalised magnitude for behavioural events, 0 for NORMAL
 * @param ts         event time, epoch milliseconds
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryEvent(
        String vehicleId,
        String driverId,
        RiskTier riskTier,
        double lat,
        double lon,
        double speedKph,
        double headingDeg,
        String segmentId,
        double speedLimitKph,
        EventType eventType,
        double severity,
        long ts) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise telemetry for " + vehicleId, e);
        }
    }

    public static TelemetryEvent fromJson(String json) {
        try {
            return MAPPER.readValue(json, TelemetryEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed telemetry payload", e);
        }
    }

    /**
     * True when this reading carries a precursor or incident signal.
     *
     * <p>Ignored for serialisation: Jackson would otherwise treat this as a bean
     * getter and write a redundant {@code behaviouralEvent} field onto every
     * message. On the highest-volume topic in the system that is wasted bytes, and
     * it would put a field on the wire that is not part of the declared schema.
     */
    @JsonIgnore
    public boolean isBehaviouralEvent() {
        return eventType != EventType.NORMAL;
    }

    /** Ratio of actual speed to the segment's posted limit; 0 when no limit is known. */
    @JsonIgnore
    public double speedOverLimitRatio() {
        return speedLimitKph <= 0 ? 0.0 : speedKph / speedLimitKph;
    }
}
