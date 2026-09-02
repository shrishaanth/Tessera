package com.tessera.fleet.durable;

/**
 * A geofence enter/exit event as stored durably (SRS §7 "Geofence event").
 *
 * @param vehicleId     the vehicle that crossed the boundary
 * @param siteId        the customer site
 * @param type          {@code ENTER} or {@code EXIT}
 * @param epochMillis   when the (debounced) transition occurred
 * @param dwellSeconds  for {@code EXIT}, the entry-to-exit duration; {@code null} for {@code ENTER} (FR-3.3)
 */
public record GeofenceEventRecord(
        String vehicleId,
        String siteId,
        Type type,
        long epochMillis,
        Integer dwellSeconds) {

    public enum Type { ENTER, EXIT }

    public static GeofenceEventRecord enter(String vehicleId, String siteId, long epochMillis) {
        return new GeofenceEventRecord(vehicleId, siteId, Type.ENTER, epochMillis, null);
    }

    public static GeofenceEventRecord exit(String vehicleId, String siteId, long epochMillis,
                                           int dwellSeconds) {
        return new GeofenceEventRecord(vehicleId, siteId, Type.EXIT, epochMillis, dwellSeconds);
    }
}
