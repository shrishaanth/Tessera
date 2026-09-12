package com.tessera.risk.reporting.hbase;

import java.util.List;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellComparator;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for decoding HBase cells.
 *
 * <p>Two failure modes are guarded. A column that is simply absent — normal in a
 * sparse store, and the state of every prediction column before training has run —
 * must not throw. And a cell whose width does not match the expected type must not
 * be decoded anyway: a {@code byte[8]} is equally a long and a double, so a
 * qualifier that changed type would otherwise return a plausible, meaningless
 * number rather than being noticed.
 */
class CellsTest {

    private static final byte[] ROW = Bytes.toBytes("VEH-001#123");
    private static final byte[] FAMILY = Bytes.toBytes("cf_score");

    @Test
    @DisplayName("values round-trip through the binary encoding")
    void valuesRoundTrip() {
        Result result = result(
                cell("riskScore", Bytes.toBytes(64.6)),
                cell("predictedLabel", Bytes.toBytes(1L)),
                cell("riskTier", Bytes.toBytes("RISKY")));

        assertThat(Cells.getDouble(result, FAMILY, "riskScore", -1)).isCloseTo(64.6, within(1e-9));
        assertThat(Cells.getLong(result, FAMILY, "predictedLabel", -1)).isEqualTo(1L);
        assertThat(Cells.getString(result, FAMILY, "riskTier", "?")).isEqualTo("RISKY");
    }

    @Test
    @DisplayName("an absent column yields the fallback rather than throwing")
    void absentColumnUsesFallback() {
        Result result = result(cell("riskScore", Bytes.toBytes(10.0)));

        assertThat(Cells.getDouble(result, FAMILY, "probability", -1.0)).isEqualTo(-1.0);
        assertThat(Cells.getLong(result, FAMILY, "predictedLabel", -7L)).isEqualTo(-7L);
        assertThat(Cells.getString(result, FAMILY, "missing", "fallback")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("an absent model column is null, distinguishable from a real zero")
    void absentModelColumnIsNull() {
        // A probability of 0.0 means the model is confident nothing will happen.
        // Absence means no model has run. A dashboard must be able to tell them apart.
        Result withoutModel = result(cell("riskScore", Bytes.toBytes(10.0)));
        assertThat(Cells.getNullableDouble(withoutModel, FAMILY, "probability")).isNull();
        assertThat(Cells.getNullableLong(withoutModel, FAMILY, "predictedLabel")).isNull();

        Result withModel = result(cell("probability", Bytes.toBytes(0.0)));
        assertThat(Cells.getNullableDouble(withModel, FAMILY, "probability")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a cell of the wrong width is not decoded anyway")
    void wrongWidthIsRejected() {
        // Four bytes is a float or an int, not a double or a long. Decoding it
        // regardless would produce a number with no relationship to what was stored.
        Result result = result(cell("riskScore", new byte[] {1, 2, 3, 4}));

        assertThat(Cells.getDouble(result, FAMILY, "riskScore", -1.0)).isEqualTo(-1.0);
        assertThat(Cells.getLong(result, FAMILY, "riskScore", -1L)).isEqualTo(-1L);
        assertThat(Cells.getNullableDouble(result, FAMILY, "riskScore")).isNull();
    }

    private static Cell cell(String qualifier, byte[] value) {
        return new KeyValue(ROW, FAMILY, Bytes.toBytes(qualifier), value);
    }

    /**
     * Cells must be sorted for {@code Result.create}; HBase's own comparator is used
     * rather than a hand-rolled one so the ordering matches what a real scan returns.
     */
    private static Result result(Cell... cells) {
        List<Cell> sorted = new java.util.ArrayList<>(List.of(cells));
        sorted.sort(CellComparator.getInstance());
        return Result.create(sorted);
    }
}
