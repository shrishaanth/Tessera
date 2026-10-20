package com.geotracker.fx.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Number, duration and time formatting for display. */
public final class Fmt {
    private Fmt() {}

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CLOCK_MS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public static String count(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    public static String rate(double perSecond) {
        if (perSecond >= 100) return String.format(Locale.US, "%,.0f", perSecond);
        return String.format(Locale.US, "%,.1f", perSecond);
    }

    public static String decimal(double v, int places) {
        return String.format(Locale.US, "%,." + places + "f", v);
    }

    /** Milliseconds with precision that suits the magnitude (e.g. 0.042 ms, 3.4 ms, 120 ms). */
    public static String millis(double ms) {
        if (ms < 0) return "–";
        if (ms < 1) return String.format(Locale.US, "%.3f ms", ms);
        if (ms < 10) return String.format(Locale.US, "%.2f ms", ms);
        if (ms < 100) return String.format(Locale.US, "%.1f ms", ms);
        return String.format(Locale.US, "%,.0f ms", ms);
    }

    public static String nanosAsMillis(long nanos) {
        return millis(nanos / 1e6);
    }

    public static String meters(double m) {
        if (m >= 1000) return String.format(Locale.US, "%.2f km", m / 1000.0);
        return String.format(Locale.US, "%.0f m", m);
    }

    public static String area(double sqm) {
        if (sqm >= 1_000_000) return String.format(Locale.US, "%.2f km²", sqm / 1e6);
        if (sqm >= 10_000) return String.format(Locale.US, "%.1f ha", sqm / 1e4);
        return String.format(Locale.US, "%,.0f m²", sqm);
    }

    public static String megabytes(long bytes) {
        return String.format(Locale.US, "%,.0f MB", bytes / (1024.0 * 1024.0));
    }

    public static String clock(long epochMillis) {
        return CLOCK.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String clockMillis(long epochMillis) {
        return CLOCK_MS.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Elapsed time as h:mm:ss or m:ss. */
    public static String duration(double seconds) {
        long s = (long) Math.floor(Math.max(0, seconds));
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** Age of an update, e.g. "0.4 s", "12 s", "3 min". */
    public static String age(long millis) {
        if (millis < 0) millis = 0;
        if (millis < 10_000) return String.format(Locale.US, "%.1f s", millis / 1000.0);
        if (millis < 120_000) return String.format(Locale.US, "%d s", millis / 1000);
        return String.format(Locale.US, "%d min", millis / 60_000);
    }

    public static String latLng(double lat, double lng) {
        return String.format(Locale.US, "%.6f, %.6f", lat, lng);
    }

    public static String coord(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
