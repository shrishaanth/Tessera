package com.tessera.risk.common.hbase;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The row-key design is the only index HBase has, so these assertions are about
 * the property the whole read path depends on: that newer rows sort first.
 */
class RowKeysTest {

    private static final long T0 = 1_767_225_600_000L; // 2026-01-01T00:00:00Z

    @Test
    @DisplayName("newer windows sort before older ones")
    void newerWindowsSortFirst() {
        String older = RowKeys.timeSeries("VEH-001", T0);
        String newer = RowKeys.timeSeries("VEH-001", T0 + 60_000);
        assertThat(newer).isLessThan(older);
    }

    @Test
    @DisplayName("a whole history sorts newest-first, not just adjacent pairs")
    void wholeHistorySortsNewestFirst() {
        List<String> keys = new ArrayList<>();
        // Spans a decimal-width change (999 -> 1000 seconds of offset), which is
        // exactly where unpadded keys would start sorting wrongly.
        for (int minute = 0; minute < 40; minute++) {
            keys.add(RowKeys.timeSeries("VEH-001", T0 + minute * 60_000L));
        }
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(null);

        List<Long> timestamps = new ArrayList<>();
        for (String key : sorted) {
            timestamps.add(RowKeys.windowStartOf(key));
        }
        assertThat(timestamps).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(timestamps.get(0)).isEqualTo(T0 + 39 * 60_000L);
    }

    @Test
    @DisplayName("every key is the same length, whatever the timestamp")
    void keysAreFixedWidth() {
        String early = RowKeys.timeSeries("SEG-00001", 1L);
        String late = RowKeys.timeSeries("SEG-00001", T0);
        assertThat(early).hasSameSizeAs(late);
        assertThat(early.length())
                .isEqualTo("SEG-00001".length() + 1 + RowKeys.TIMESTAMP_WIDTH);
    }

    @Test
    @DisplayName("entity and window start survive a round trip")
    void roundTrip() {
        String key = RowKeys.timeSeries("SEG-00238", T0 + 12_345);
        assertThat(RowKeys.entityOf(key)).isEqualTo("SEG-00238");
        assertThat(RowKeys.windowStartOf(key)).isEqualTo(T0 + 12_345);
    }

    @Test
    @DisplayName("a scan range covers one entity and stops before the next")
    void scanRangeIsBoundedToOneEntity() {
        String key = RowKeys.timeSeries("VEH-001", T0);
        assertThat(key).isGreaterThanOrEqualTo(RowKeys.scanStartForEntity("VEH-001"));
        assertThat(key).isLessThan(RowKeys.scanStopForEntity("VEH-001"));
        // VEH-0011 shares a prefix with VEH-001; the stop key must exclude it, or a
        // scan for one vehicle would silently return another's rows.
        assertThat(RowKeys.timeSeries("VEH-0011", T0))
                .isGreaterThanOrEqualTo(RowKeys.scanStopForEntity("VEH-001"));
    }

    @Test
    @DisplayName("an entity id containing the separator is rejected")
    void separatorInEntityIsRejected() {
        assertThatThrownBy(() -> RowKeys.timeSeries("VEH#001", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a negative timestamp is rejected rather than breaking the padding")
    void negativeTimestampIsRejected() {
        assertThatThrownBy(() -> RowKeys.timeSeries("VEH-001", -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
