package com.tessera.risk.archive;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for how the fleet is divided across tasks.
 *
 * <p>Every vehicle must appear in exactly one slice. A vehicle in two slices would
 * be simulated twice and its readings duplicated in the archive; a vehicle in none
 * would silently vanish from the training data. Neither shows up as an error —
 * both just produce a corpus that quietly misrepresents the fleet.
 */
class ArchiveSlicingTest {

    @Test
    @DisplayName("slices cover every vehicle exactly once")
    void slicesPartitionTheFleet() {
        assertPartitions(24, 4);
        assertPartitions(24, 7);   // does not divide evenly
        assertPartitions(1, 4);    // fewer vehicles than slices
        assertPartitions(30, 1);
    }

    @Test
    @DisplayName("slices differ in size by at most one vehicle")
    void slicesAreBalanced() {
        List<int[]> slices = ArchiveMain.sliceFleet(24, 7);
        int smallest = slices.stream().mapToInt(s -> s[1] - s[0]).min().orElseThrow();
        int largest = slices.stream().mapToInt(s -> s[1] - s[0]).max().orElseThrow();
        // One task running twice as long as the others would set the job's runtime.
        assertThat(largest - smallest).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("asking for more slices than vehicles does not create empty tasks")
    void doesNotCreateEmptySlices() {
        List<int[]> slices = ArchiveMain.sliceFleet(3, 10);
        assertThat(slices).hasSize(3);
        assertThat(slices).allSatisfy(slice -> assertThat(slice[1] - slice[0]).isPositive());
    }

    @Test
    @DisplayName("a fleet is never split into zero slices")
    void alwaysAtLeastOneSlice() {
        assertThat(ArchiveMain.sliceFleet(24, 0)).hasSize(1);
        assertThat(ArchiveMain.sliceFleet(24, -3)).hasSize(1);
    }

    private static void assertPartitions(int vehicleCount, int requestedSlices) {
        List<int[]> slices = ArchiveMain.sliceFleet(vehicleCount, requestedSlices);

        boolean[] seen = new boolean[vehicleCount];
        for (int[] slice : slices) {
            for (int v = slice[0]; v < slice[1]; v++) {
                assertThat(seen[v])
                        .as("vehicle %d appears in more than one slice", v)
                        .isFalse();
                seen[v] = true;
            }
        }
        for (int v = 0; v < vehicleCount; v++) {
            assertThat(seen[v]).as("vehicle %d appears in no slice", v).isTrue();
        }
    }
}
