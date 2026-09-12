package com.tessera.risk.common.model;

/**
 * Classification attached to a telemetry reading.
 *
 * <p>{@link #HARD_BRAKE} and {@link #SPEED_VIOLATION} are <em>precursors</em>:
 * frequent, lower-severity signals. {@link #INCIDENT} is the rarer, more severe
 * outcome the model is trained to predict from a recent run of precursors —
 * keeping them distinct is what stops the prediction task from being circular.
 */
public enum EventType {
    /** An ordinary reading; no unsafe behaviour detected. */
    NORMAL,
    /** Deceleration beyond the configured safe threshold between consecutive ticks. */
    HARD_BRAKE,
    /** Sustained travel above the segment's posted limit by the configured margin. */
    SPEED_VIOLATION,
    /** Severe event: extreme deceleration, or a hard brake while already speeding. */
    INCIDENT
}
