package com.tessera.risk.common.model;

/**
 * Display names for drivers, derived deterministically from the driver id.
 *
 * <p>The name is a pure function of the identifier, which is what allows two
 * unrelated services to agree on it without the name ever travelling on the wire.
 * The producer needs it to describe its roster; the streaming job needs it to fill
 * the {@code driver_profile} reference table. Putting it on every telemetry record
 * instead would add a redundant string to the highest-volume topic in the system
 * purely for cosmetics.
 *
 * <p>These are invented names for invented drivers. No real person is described.
 */
public final class DriverDirectory {

    private static final String[] NAMES = {
            "A. Okafor", "B. Nguyen", "C. Delgado", "D. Petrov", "E. Haddad",
            "F. Larsson", "G. Ibrahim", "H. Kowalski", "I. Santos", "J. Meyer",
            "K. Abebe", "L. Romano", "M. Fernandez", "N. Dubois", "O. Yamamoto",
            "P. Novak", "Q. Rahman", "R. Andersson", "S. Kaur", "T. Bauer",
            "U. Costa", "V. Popescu", "W. Sorensen", "X. Fisher", "Y. Kovac",
            "Z. Moreau", "A. Bianchi", "B. Schmidt", "C. Walsh", "D. Fournier",
    };

    private DriverDirectory() {
    }

    /**
     * Name for a driver id of the form {@code DRV-007}.
     *
     * <p>Falls back to the identifier itself for anything unparseable, so an
     * unexpected id shows up in the interface as itself rather than silently
     * borrowing some other driver's name.
     */
    public static String nameFor(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            return "unknown";
        }
        int dash = driverId.lastIndexOf('-');
        if (dash < 0 || dash == driverId.length() - 1) {
            return driverId;
        }
        try {
            int ordinal = Integer.parseInt(driverId.substring(dash + 1));
            return NAMES[Math.floorMod(ordinal - 1, NAMES.length)];
        } catch (NumberFormatException e) {
            return driverId;
        }
    }
}
