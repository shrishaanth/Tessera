package com.tessera.risk.reporting.hbase;

import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * Decodes HBase cells back into Java values.
 *
 * <h2>Why every read is null-tolerant</h2>
 * A column being absent is a normal state here, not a fault, and there are two
 * separate reasons for it. The model's {@code predictedLabel} and
 * {@code probability} exist only after training has run, so every row written
 * before that has neither. And HBase is not a relational store: a row is a sparse
 * set of cells, so "column not present" is simply what an unwritten value looks
 * like — there is no schema to give it a default.
 *
 * <p>So each accessor takes a fallback rather than throwing. The alternative is a
 * service that returns 500 for a fleet that has not been scored yet, which is the
 * ordinary state of a freshly started stack.
 *
 * <h2>Why the decoding is explicit</h2>
 * Values are stored in HBase's binary encoding, and the encoding is not recorded
 * in the cell — a {@code byte[8]} is equally a double or a long. The writer's
 * choice per qualifier is therefore knowledge the reader has to hold too, which is
 * why the length is checked before decoding: a qualifier that changed type would
 * otherwise return a plausible but meaningless number rather than failing.
 */
final class Cells {

    private Cells() {
    }

    static double getDouble(Result result, byte[] family, String qualifier, double fallback) {
        byte[] value = result.getValue(family, Bytes.toBytes(qualifier));
        return value == null || value.length != Bytes.SIZEOF_DOUBLE
                ? fallback : Bytes.toDouble(value);
    }

    static long getLong(Result result, byte[] family, String qualifier, long fallback) {
        byte[] value = result.getValue(family, Bytes.toBytes(qualifier));
        return value == null || value.length != Bytes.SIZEOF_LONG
                ? fallback : Bytes.toLong(value);
    }

    static String getString(Result result, byte[] family, String qualifier, String fallback) {
        byte[] value = result.getValue(family, Bytes.toBytes(qualifier));
        return value == null ? fallback : Bytes.toString(value);
    }

    /**
     * A double that may genuinely be absent, as {@code null} rather than a sentinel.
     *
     * <p>Used for the model's outputs. A probability of 0.0 means "the model is
     * confident there will be no incident"; absence means "no model has run". Those
     * are different statements and a dashboard should not show the second as the
     * first.
     */
    static Double getNullableDouble(Result result, byte[] family, String qualifier) {
        byte[] value = result.getValue(family, Bytes.toBytes(qualifier));
        return value == null || value.length != Bytes.SIZEOF_DOUBLE
                ? null : Bytes.toDouble(value);
    }

    /** A long that may genuinely be absent; see {@link #getNullableDouble}. */
    static Long getNullableLong(Result result, byte[] family, String qualifier) {
        byte[] value = result.getValue(family, Bytes.toBytes(qualifier));
        return value == null || value.length != Bytes.SIZEOF_LONG
                ? null : Bytes.toLong(value);
    }
}
