package com.tessera.fleet.alert;

/**
 * A dispatcher-visible exception (deck: "Alert / Exception Feed").
 *
 * @param id           generated id
 * @param type         alert kind
 * @param severity     {@code INFO} / {@code WARNING}
 * @param vehicleId    vehicle involved, or {@code null}
 * @param siteId       site involved, or {@code null}
 * @param message      human-readable summary
 * @param createdAtEpochMs when raised
 * @param acknowledged whether a dispatcher has cleared it
 */
public record Alert(
        String id,
        Type type,
        Severity severity,
        String vehicleId,
        String siteId,
        String message,
        long createdAtEpochMs,
        boolean acknowledged) {

    public enum Type { DWELL_EXCEEDED }

    public enum Severity { INFO, WARNING }

    public Alert acknowledgedCopy() {
        return new Alert(id, type, severity, vehicleId, siteId, message, createdAtEpochMs, true);
    }
}
