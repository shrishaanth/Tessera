package com.tessera.fleet.model;

public enum JobStatus {
    /** Created, no vehicle assigned yet. */
    UNASSIGNED,
    /** A vehicle has been assigned (FR-2.4). */
    ASSIGNED,
    /** Vehicle reported on site / job finished (Phase 2+ closes the loop). */
    COMPLETED,
    CANCELLED
}
