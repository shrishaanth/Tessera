package com.tessera.risk.streaming.hbase;

/**
 * Row-key construction for the time-series tables.
 *
 * <h2>Why the timestamp is reversed</h2>
 * HBase stores rows in lexicographic order of the row key and has no secondary
 * index, so the key <em>is</em> the access path. Every question this system asks
 * of HBase is "what has this entity done most recently", and a plain ascending
 * timestamp puts the answer at the <em>end</em> of the entity's key range — you
 * would have to scan the whole history to reach it.
 *
 * <p>Storing {@code Long.MAX_VALUE - windowStart} inverts that ordering, so the
 * newest window for an entity is the first row in its range. "Latest state" then
 * becomes a scan with a limit of one, and "the last hour" a short range scan, both
 * independent of how much history has accumulated.
 *
 * <h2>Why the digits are padded</h2>
 * The key is text, so it sorts as text: without a fixed width, {@code 9999} would
 * sort after {@code 10000} and the ordering the reversal bought would be lost.
 * Nineteen digits is the width of {@link Long#MAX_VALUE}, so every key produced by
 * this class is the same length.
 *
 * <h2>The hot-spotting trade-off</h2>
 * The entity identifier leads the key, which keeps one entity's history
 * contiguous — exactly what makes those scans cheap. The cost is that all writes
 * for a busy entity land in one region. At this system's scale that is not a
 * practical problem, and the mitigation if it became one is recorded in the SRS:
 * prefix the key with a hash-derived salt, or bucket the timestamp by hour.
 */
public final class RowKeys {

    /** Separator between the entity identifier and the reversed timestamp. */
    public static final char SEPARATOR = '#';

    /** Digits in {@link Long#MAX_VALUE}; every reversed timestamp is padded to this. */
    public static final int TIMESTAMP_WIDTH = 19;

    private RowKeys() {
    }

    /** Row key for one entity's window, e.g. {@code SEG-00238#9223370247862775807}. */
    public static String timeSeries(String entityId, long windowStartMillis) {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("Row key needs an entity id");
        }
        if (entityId.indexOf(SEPARATOR) >= 0) {
            // Otherwise the key could not be parsed back apart, and two different
            // entities could collide on one row.
            throw new IllegalArgumentException(
                    "Entity id must not contain '" + SEPARATOR + "': " + entityId);
        }
        return entityId + SEPARATOR + String.format("%0" + TIMESTAMP_WIDTH + "d",
                reverse(windowStartMillis));
    }

    /** Row key for a reference row that has no time dimension, e.g. a driver profile. */
    public static String reference(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("Row key needs an entity id");
        }
        return entityId;
    }

    /**
     * {@code Long.MAX_VALUE - millis}. Self-inverse, so the same method decodes it.
     *
     * @throws IllegalArgumentException for a negative timestamp, which would
     *         produce a key wider than the padding and break the ordering silently
     */
    public static long reverse(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("Timestamp must not be negative: " + millis);
        }
        return Long.MAX_VALUE - millis;
    }

    /** Entity identifier from a key built by {@link #timeSeries}. */
    public static String entityOf(String rowKey) {
        int at = rowKey.lastIndexOf(SEPARATOR);
        return at < 0 ? rowKey : rowKey.substring(0, at);
    }

    /** Window start, in epoch millis, from a key built by {@link #timeSeries}. */
    public static long windowStartOf(String rowKey) {
        int at = rowKey.lastIndexOf(SEPARATOR);
        if (at < 0) {
            throw new IllegalArgumentException("Not a time-series row key: " + rowKey);
        }
        return reverse(Long.parseLong(rowKey.substring(at + 1)));
    }

    /**
     * Inclusive start of a scan over one entity, which because of the reversal is
     * the <em>newest</em> row. Pair with {@link #scanStopForEntity}.
     */
    public static String scanStartForEntity(String entityId) {
        return entityId + SEPARATOR;
    }

    /** Exclusive stop of a scan over one entity. */
    public static String scanStopForEntity(String entityId) {
        return entityId + (char) (SEPARATOR + 1);
    }
}
