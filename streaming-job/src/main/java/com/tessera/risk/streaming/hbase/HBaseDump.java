package com.tessera.risk.streaming.hbase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;

import com.tessera.risk.streaming.StreamingConfig;

/**
 * Prints rows from the risk tables in a readable form.
 *
 * <p>Values are stored in HBase's binary encoding, which the {@code hbase shell}
 * renders as escaped bytes — fine for the machine, useless for confirming a
 * pipeline works. This decodes them back, and because the row keys carry a
 * reversed timestamp it also shows the newest windows first with no sorting
 * needed, which doubles as a demonstration that the key design does what it
 * claims.
 *
 * <pre>
 *   java -cp streaming-job.jar com.tessera.risk.streaming.hbase.HBaseDump vehicle_risk 10
 * </pre>
 *
 * @see RowKeys
 */
public final class HBaseDump {

    /** Qualifiers holding text; everything else is decoded as a number. */
    private static final List<String> TEXT_QUALIFIERS = List.of(
            HBaseWriter.Q_DRIVER_ID, HBaseWriter.Q_DRIVER_NAME, HBaseWriter.Q_RISK_TIER);

    /** Qualifiers holding a whole number rather than a double. */
    private static final List<String> LONG_QUALIFIERS = List.of(
            HBaseWriter.Q_VEHICLE_COUNT, HBaseWriter.Q_READING_COUNT,
            HBaseWriter.Q_HARD_BRAKE_COUNT, HBaseWriter.Q_SPEED_VIOLATION_COUNT,
            HBaseWriter.Q_INCIDENT_COUNT, HBaseWriter.Q_WINDOW_END,
            HBaseWriter.Q_LAST_SEEN_TS, HBaseWriter.Q_PREDICTED_LABEL);

    private HBaseDump() {
    }

    public static void main(String[] args) throws Exception {
        String table = args.length > 0 ? args[0] : HBaseSchema.VEHICLE_RISK.getNameAsString();
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        StreamingConfig config = StreamingConfig.fromEnvironment();

        try (Connection connection = ConnectionFactory.createConnection(
                HBaseSchema.configuration(config.hbaseZkQuorum(), config.hbaseZkPort()))) {
            for (String line : scan(connection, table, limit)) {
                System.out.println(line);
            }
        }
    }

    /** Up to {@code limit} rows of {@code table}, one formatted line per row. */
    public static List<String> scan(Connection connection, String table, int limit) throws Exception {
        List<String> out = new ArrayList<>();
        try (Table handle = connection.getTable(TableName.valueOf(table));
             ResultScanner scanner = handle.getScanner(new Scan().setLimit(limit))) {
            for (Result result : scanner) {
                out.add(format(result));
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private static String format(Result result) {
        String rowKey = Bytes.toString(result.getRow());
        StringBuilder line = new StringBuilder();
        line.append(RowKeys.entityOf(rowKey));
        // A reference table's key carries no timestamp, which is not an error — so
        // the label is decided before anything is appended, or a failed parse would
        // leave a dangling "window=" in front of it.
        String when;
        try {
            when = "window=" + Instant.ofEpochMilli(RowKeys.windowStartOf(rowKey));
        } catch (RuntimeException e) {
            when = "(reference row)";
        }
        line.append("  ").append(when);

        NavigableMap<String, String> values = new TreeMap<>();
        for (Cell cell : result.rawCells()) {
            String family = Bytes.toString(CellUtil.cloneFamily(cell));
            String qualifier = Bytes.toString(CellUtil.cloneQualifier(cell));
            values.put(family + ":" + qualifier, decode(qualifier, CellUtil.cloneValue(cell)));
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            line.append("  ").append(entry.getKey()).append('=').append(entry.getValue());
        }
        return line.toString();
    }

    private static String decode(String qualifier, byte[] value) {
        if (TEXT_QUALIFIERS.contains(qualifier)) {
            return Bytes.toString(value);
        }
        if (LONG_QUALIFIERS.contains(qualifier)) {
            return value.length == Bytes.SIZEOF_LONG
                    ? Long.toString(Bytes.toLong(value)) : Bytes.toString(value);
        }
        return value.length == Bytes.SIZEOF_DOUBLE
                ? String.format("%.3f", Bytes.toDouble(value)) : Bytes.toString(value);
    }
}
