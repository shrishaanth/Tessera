package com.tessera.risk.common.spark;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.model.TelemetryEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one place where a change cannot fail loudly.
 *
 * <p>The Spark schema and the {@link TelemetryEvent} record describe the same
 * bytes, but nothing connects them at compile time. Rename a record component and
 * the producer emits the new name while Spark keeps looking for the old one —
 * which does not throw. It yields a column of nulls, every aggregate silently
 * becomes zero, and the pipeline goes on reporting that the entire fleet is
 * driving perfectly.
 */
class TelemetrySchemaTest {

    @Test
    @DisplayName("the Spark schema covers exactly the fields the event carries")
    void schemaMatchesTheEventRecord() {
        List<String> onTheWire = Arrays.stream(TelemetryEvent.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        List<String> inTheSchema = Arrays.asList(TelemetrySchema.TELEMETRY.fieldNames());

        assertThat(inTheSchema).containsExactlyInAnyOrderElementsOf(onTheWire);
    }

    @Test
    @DisplayName("the event-time column name does not collide with a payload field")
    void eventTimeColumnIsDistinct() {
        assertThat(Arrays.asList(TelemetrySchema.TELEMETRY.fieldNames()))
                .doesNotContain(TelemetrySchema.EVENT_TIME);
    }
}
