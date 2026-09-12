package com.tessera.risk.reporting.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the transparency disclosure (FR-6.1).
 *
 * <p>Asserted rather than trusted because this is a requirement about honesty, and
 * the failure mode is a dashboard that quietly stops saying its data is invented.
 * Nothing else in the system would notice.
 */
class DataSourceInfoTest {

    @Test
    @DisplayName("the disclosure states plainly that the data is simulated")
    void statesThatDataIsSimulated() {
        DataSourceInfo info = DataSourceInfo.simulated("Downtown Boston (demo area)");

        assertThat(info.simulated()).isTrue();
        assertThat(info.headline()).containsIgnoringCase("simulated");
        // Not a single vague sentence: FR-6.2 requires it not be minimised, and
        // detail is what makes a disclosure usable rather than a disclaimer.
        assertThat(info.details()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("it says no real person or vehicle is described")
    void deniesRealSubjects() {
        DataSourceInfo info = DataSourceInfo.simulated("Anywhere");
        String all = String.join(" ", info.details());

        assertThat(all).containsIgnoringCase("no physical vehicles");
        assertThat(all).containsIgnoringCase("no real");
        assertThat(all).containsIgnoringCase("personal data");
    }

    @Test
    @DisplayName("a blank area name does not leave dangling punctuation")
    void handlesMissingAreaName() {
        DataSourceInfo info = DataSourceInfo.simulated("  ");
        assertThat(info.details().get(0)).doesNotContain("covering .");
        assertThat(info.details().get(0)).endsWith("network.");
    }
}
