package com.tessera.risk.archive;

import java.util.ArrayList;
import java.util.List;

import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;

import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.common.spark.TelemetrySchema;

/**
 * Converts simulated telemetry into Spark rows matching {@link TelemetrySchema}.
 *
 * <p>Rows are built positionally, so the order here and the field order in the
 * schema are the same fact written twice. {@code TelemetryRowsTest} asserts they
 * agree, because getting it wrong does not throw — it silently transposes two
 * columns of the same type, and the archive would then train a model on latitudes
 * labelled as longitudes.
 *
 * <p>Enums are written as their {@code name()} string rather than as an ordinal.
 * Ordinals would be smaller, but they break the moment a constant is inserted into
 * the middle of the enum, and a Parquet archive outlives the code that wrote it.
 */
public final class TelemetryRows {

    private TelemetryRows() {
    }

    /** One Spark row, positionally matching {@link TelemetrySchema#TELEMETRY}. */
    public static Row toRow(TelemetryEvent event) {
        return RowFactory.create(
                event.vehicleId(),
                event.driverId(),
                event.riskTier().name(),
                event.lat(),
                event.lon(),
                event.speedKph(),
                event.headingDeg(),
                event.segmentId(),
                event.speedLimitKph(),
                event.eventType().name(),
                event.severity(),
                event.ts());
    }

    /** Converts a batch, preserving order. */
    public static List<Row> toRows(List<TelemetryEvent> events) {
        List<Row> rows = new ArrayList<>(events.size());
        for (TelemetryEvent event : events) {
            rows.add(toRow(event));
        }
        return rows;
    }
}
