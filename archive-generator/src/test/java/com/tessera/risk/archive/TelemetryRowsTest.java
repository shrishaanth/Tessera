package com.tessera.risk.archive;

import org.apache.spark.sql.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.model.EventType;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.common.spark.TelemetrySchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the positional mapping from event to Spark row.
 *
 * <p>Rows are built by position, so every field is asserted by looking its index up
 * in the schema by name. Getting this wrong is a silent failure: transposing two
 * columns of the same type writes a valid Parquet file in which latitude is stored
 * as longitude, and nothing downstream would ever complain. Each field below is
 * given a distinct value for exactly that reason — a test using the same number
 * twice could not tell a swap from a correct mapping.
 */
class TelemetryRowsTest {

    private static final TelemetryEvent EVENT = new TelemetryEvent(
            "VEH-007", "DRV-007", RiskTier.RISKY,
            42.3601, -71.0589, 47.5, 123.4,
            "SEG-00238", 50.0, EventType.HARD_BRAKE, 0.82, 1_767_225_600_000L);

    @Test
    @DisplayName("every field lands in the column the schema names")
    void fieldsLandInTheRightColumns() {
        Row row = TelemetryRows.toRow(EVENT);

        assertThat(valueOf(row, "vehicleId")).isEqualTo("VEH-007");
        assertThat(valueOf(row, "driverId")).isEqualTo("DRV-007");
        assertThat(valueOf(row, "riskTier")).isEqualTo("RISKY");
        assertThat(valueOf(row, "lat")).isEqualTo(42.3601);
        assertThat(valueOf(row, "lon")).isEqualTo(-71.0589);
        assertThat(valueOf(row, "speedKph")).isEqualTo(47.5);
        assertThat(valueOf(row, "headingDeg")).isEqualTo(123.4);
        assertThat(valueOf(row, "segmentId")).isEqualTo("SEG-00238");
        assertThat(valueOf(row, "speedLimitKph")).isEqualTo(50.0);
        assertThat(valueOf(row, "eventType")).isEqualTo("HARD_BRAKE");
        assertThat(valueOf(row, "severity")).isEqualTo(0.82);
        assertThat(valueOf(row, "ts")).isEqualTo(1_767_225_600_000L);
    }

    @Test
    @DisplayName("the row has exactly as many fields as the schema")
    void rowWidthMatchesTheSchema() {
        // A field added to the schema but not to the row builder would otherwise
        // surface as an ArrayIndexOutOfBounds deep inside Spark, or worse, as a
        // silently null column.
        assertThat(TelemetryRows.toRow(EVENT).size())
                .isEqualTo(TelemetrySchema.TELEMETRY.fields().length);
    }

    @Test
    @DisplayName("latitude and longitude are not interchanged")
    void coordinatesAreNotSwapped() {
        // Called out separately because it is the swap that would do real damage and
        // the one a casual reading of the code cannot rule out: both are doubles,
        // adjacent, and named alike.
        Row row = TelemetryRows.toRow(EVENT);
        assertThat((Double) valueOf(row, "lat")).isPositive();
        assertThat((Double) valueOf(row, "lon")).isNegative();
    }

    @Test
    @DisplayName("enums are written as names, not ordinals")
    void enumsAreWrittenAsNames() {
        // An ordinal would be smaller but breaks the moment a constant is inserted
        // into the middle of the enum, and the archive outlives the code.
        Row row = TelemetryRows.toRow(EVENT);
        assertThat(valueOf(row, "eventType")).isInstanceOf(String.class);
        assertThat(valueOf(row, "riskTier")).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("a batch preserves order")
    void batchPreservesOrder() {
        TelemetryEvent second = new TelemetryEvent(
                "VEH-008", "DRV-008", RiskTier.SAFE, 1.0, 2.0, 3.0, 4.0,
                "SEG-00001", 30.0, EventType.NORMAL, 0.0, 1_767_225_601_000L);

        assertThat(TelemetryRows.toRows(java.util.List.of(EVENT, second)))
                .extracting(row -> valueOf(row, "vehicleId"))
                .containsExactly("VEH-007", "VEH-008");
    }

    private static Object valueOf(Row row, String column) {
        return row.get(TelemetrySchema.TELEMETRY.fieldIndex(column));
    }
}
