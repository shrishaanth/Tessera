package com.tessera.risk.streaming.hbase;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.spark.api.java.function.ForeachPartitionFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;

import com.tessera.risk.common.model.DriverDirectory;
import com.tessera.risk.streaming.RiskWindows;

/**
 * Writes aggregated windows into HBase from inside a micro-batch.
 *
 * <h2>Where the connection lives</h2>
 * An HBase {@link Connection} is not serializable, so it cannot be built on the
 * driver and shipped: only the two connection strings cross the wire, and the
 * connection is obtained on the executor from {@link HBaseConnections}, which
 * keeps one per JVM. The {@code BufferedMutator} is per-partition, because it
 * holds the write buffer and closing it is what flushes.
 *
 * <h2>Why re-writing a window is safe</h2>
 * The query runs in update mode, so an open window is emitted again every time new
 * data lands in it, and the same row key is written repeatedly until the window
 * closes. That is not a problem to be designed around: an HBase {@link Put} is an
 * upsert, so the row simply converges on its final value. The same property makes
 * a Spark task retry harmless, which is what lets this sink be correct without
 * transactions.
 *
 * <h2>Encoding</h2>
 * Numbers are stored in HBase's binary encoding rather than as text. It is half
 * the size, needs no parsing on read, and {@code Bytes.toBytes(long)} preserves
 * ordering, so a numeric qualifier could be used in a key later without
 * re-encoding. The cost is that a raw {@code scan} in the HBase shell prints
 * escaped bytes; {@link HBaseDump} exists to read these tables back in a readable
 * form.
 */
public final class HBaseWriter implements Serializable {

    private static final long serialVersionUID = 1L;

    // Column qualifiers. A qualifier carries the same meaning wherever it appears,
    // so one that is written to more than one table is declared once: the
    // hardBrakeCount in cf_risk and the one in cf_behavior count the same thing
    // about different subjects, and a reader should never have to check whether
    // two identically named columns agree.

    // Counts, shared by segment_metrics/cf_risk and vehicle_risk/cf_behavior.
    public static final String Q_HARD_BRAKE_COUNT = "hardBrakeCount";
    public static final String Q_SPEED_VIOLATION_COUNT = "speedViolationCount";
    public static final String Q_INCIDENT_COUNT = "incidentCount";
    public static final String Q_READING_COUNT = "readingCount";

    // Speed, shared by segment_metrics/cf_traffic and vehicle_risk/cf_behavior.
    public static final String Q_AVG_SPEED = "avgSpeedKph";
    public static final String Q_MAX_SPEED = "maxSpeedKph";
    public static final String Q_SPEED_LIMIT = "speedLimitKph";
    public static final String Q_AVG_OVER_LIMIT = "avgOverLimitRatio";

    /** Window end, stored so a reader knows the span without re-deriving it from the key. */
    public static final String Q_WINDOW_END = "windowEnd";

    // segment_metrics only.
    public static final String Q_VEHICLE_COUNT = "vehicleCount";
    public static final String Q_COMPLIANCE_RATIO = "complianceRatio";
    public static final String Q_RISK_INDEX = "riskIndex";

    // vehicle_risk only.
    public static final String Q_MAX_SEVERITY = "maxSeverity";
    public static final String Q_DRIVER_ID = "driverId";
    public static final String Q_RISK_TIER = "riskTier";

    // vehicle_risk / cf_score
    public static final String Q_RISK_SCORE = "riskScore";
    /**
     * Written by the model-scoring stage (FR-3.6), not by this writer. Defined here
     * so the two writers cannot disagree on the qualifier name, and so the column
     * is visible in the schema before the model exists.
     */
    public static final String Q_PREDICTED_LABEL = "predictedLabel";
    /** Model confidence for {@link #Q_PREDICTED_LABEL}; see above. */
    public static final String Q_PROBABILITY = "probability";

    // driver_profile / cf_profile
    public static final String Q_DRIVER_NAME = "driverName";
    public static final String Q_LAST_SEEN_TS = "lastSeenTs";

    private final String zkQuorum;
    private final String zkPort;

    public HBaseWriter(String zkQuorum, String zkPort) {
        this.zkQuorum = zkQuorum;
        this.zkPort = zkPort;
    }

    /** One row per segment per window, keyed {@code segmentId#reverseTs}. */
    public void writeSegmentMetrics(Dataset<Row> batch) {
        write(batch, HBaseSchema.SEGMENT_METRICS, (row, put) -> {
            putDouble(put, HBaseSchema.CF_TRAFFIC, Q_AVG_SPEED, row, RiskWindows.AVG_SPEED_KPH);
            putLong(put, HBaseSchema.CF_TRAFFIC, Q_VEHICLE_COUNT, row, RiskWindows.VEHICLE_COUNT);
            putDouble(put, HBaseSchema.CF_TRAFFIC, Q_SPEED_LIMIT, row, RiskWindows.SPEED_LIMIT_KPH);
            putDouble(put, HBaseSchema.CF_TRAFFIC, Q_COMPLIANCE_RATIO, row, RiskWindows.COMPLIANCE_RATIO);
            putLong(put, HBaseSchema.CF_TRAFFIC, Q_READING_COUNT, row, RiskWindows.READING_COUNT);
            putLong(put, HBaseSchema.CF_TRAFFIC, Q_WINDOW_END, row, RiskWindows.WINDOW_END);

            putLong(put, HBaseSchema.CF_RISK, Q_HARD_BRAKE_COUNT, row, RiskWindows.HARD_BRAKE_COUNT);
            putLong(put, HBaseSchema.CF_RISK, Q_SPEED_VIOLATION_COUNT, row, RiskWindows.SPEED_VIOLATION_COUNT);
            putLong(put, HBaseSchema.CF_RISK, Q_INCIDENT_COUNT, row, RiskWindows.INCIDENT_COUNT);
            putDouble(put, HBaseSchema.CF_RISK, Q_RISK_INDEX, row, RiskWindows.RISK_INDEX);
        }, RiskWindows.SEGMENT_ID);
    }

    /** One row per vehicle per window, keyed {@code vehicleId#reverseTs}. */
    public void writeVehicleRisk(Dataset<Row> batch) {
        write(batch, HBaseSchema.VEHICLE_RISK, (row, put) -> {
            putLong(put, HBaseSchema.CF_BEHAVIOR, Q_HARD_BRAKE_COUNT, row, RiskWindows.HARD_BRAKE_COUNT);
            putLong(put, HBaseSchema.CF_BEHAVIOR, Q_SPEED_VIOLATION_COUNT, row, RiskWindows.SPEED_VIOLATION_COUNT);
            putLong(put, HBaseSchema.CF_BEHAVIOR, Q_INCIDENT_COUNT, row, RiskWindows.INCIDENT_COUNT);
            putLong(put, HBaseSchema.CF_BEHAVIOR, Q_READING_COUNT, row, RiskWindows.READING_COUNT);
            putDouble(put, HBaseSchema.CF_BEHAVIOR, Q_AVG_SPEED, row, RiskWindows.AVG_SPEED_KPH);
            putDouble(put, HBaseSchema.CF_BEHAVIOR, Q_MAX_SPEED, row, RiskWindows.MAX_SPEED_KPH);
            putDouble(put, HBaseSchema.CF_BEHAVIOR, Q_AVG_OVER_LIMIT, row, RiskWindows.AVG_OVER_LIMIT_RATIO);
            putDouble(put, HBaseSchema.CF_BEHAVIOR, Q_MAX_SEVERITY, row, RiskWindows.MAX_SEVERITY);
            putString(put, HBaseSchema.CF_BEHAVIOR, Q_DRIVER_ID, row, RiskWindows.DRIVER_ID);
            putString(put, HBaseSchema.CF_BEHAVIOR, Q_RISK_TIER, row, RiskWindows.RISK_TIER);
            putLong(put, HBaseSchema.CF_BEHAVIOR, Q_WINDOW_END, row, RiskWindows.WINDOW_END);

            putDouble(put, HBaseSchema.CF_SCORE, Q_RISK_SCORE, row, RiskWindows.RISK_SCORE);
        }, RiskWindows.VEHICLE_ID);
    }

    /**
     * Reference rows for the drivers seen in this batch, keyed by vehicle.
     *
     * <p>Derived from the stream rather than loaded from a roster file, so the
     * table is populated by the act of the fleet reporting in and stays correct if
     * a vehicle is reassigned. It is a handful of rows per batch — one per active
     * vehicle — rewritten each time, which costs nothing and needs no separate
     * bootstrap step.
     */
    public void writeDriverProfiles(Dataset<Row> vehicleBatch) {
        Dataset<Row> profiles = vehicleBatch
                .select(RiskWindows.VEHICLE_ID, RiskWindows.DRIVER_ID,
                        RiskWindows.RISK_TIER, RiskWindows.WINDOW_END)
                .dropDuplicates(RiskWindows.VEHICLE_ID);

        String quorum = zkQuorum;
        String port = zkPort;
        profiles.foreachPartition((ForeachPartitionFunction<Row>) rows -> {
            if (!rows.hasNext()) {
                return;
            }
            Connection connection = HBaseConnections.get(quorum, port);
            try (BufferedMutator mutator =
                         connection.getBufferedMutator(HBaseSchema.DRIVER_PROFILE)) {
                while (rows.hasNext()) {
                    Row row = rows.next();
                    String vehicleId = row.getAs(RiskWindows.VEHICLE_ID);
                    String driverId = row.getAs(RiskWindows.DRIVER_ID);
                    Put put = new Put(bytes(RowKeys.reference(vehicleId)));
                    putString(put, HBaseSchema.CF_PROFILE, Q_DRIVER_ID, row, RiskWindows.DRIVER_ID);
                    put.addColumn(HBaseSchema.CF_PROFILE, bytes(Q_DRIVER_NAME),
                            bytes(DriverDirectory.nameFor(driverId)));
                    putString(put, HBaseSchema.CF_PROFILE, Q_RISK_TIER, row, RiskWindows.RISK_TIER);
                    putLong(put, HBaseSchema.CF_PROFILE, Q_LAST_SEEN_TS, row, RiskWindows.WINDOW_END);
                    mutator.mutate(put);
                }
            }
        });
    }

    private void write(Dataset<Row> batch, TableName table, RowMapper mapper, String entityColumn) {
        String quorum = zkQuorum;
        String port = zkPort;
        // TableName is not serializable, so the closure carries its text and
        // rebuilds it on the executor. Capturing the object itself compiles
        // cleanly and then fails at run time inside task serialisation.
        String tableName = table.getNameAsString();
        batch.foreachPartition((ForeachPartitionFunction<Row>) rows -> {
            // An empty partition is common once the window set stabilises, and
            // there is nothing to do for one.
            if (!rows.hasNext()) {
                return;
            }
            // The connection is shared for the life of the JVM; the mutator is
            // per-partition and cheap, and closing it is what flushes the buffer.
            Connection connection = HBaseConnections.get(quorum, port);
            try (BufferedMutator mutator =
                         connection.getBufferedMutator(TableName.valueOf(tableName))) {
                mutate(rows, mapper, entityColumn, mutator);
            }
        });
    }

    private static void mutate(Iterator<Row> rows, RowMapper mapper, String entityColumn,
                               BufferedMutator mutator) throws IOException {
        while (rows.hasNext()) {
            Row row = rows.next();
            String entityId = row.getAs(entityColumn);
            Long windowStart = row.getAs(RiskWindows.WINDOW_START);
            if (entityId == null || windowStart == null) {
                continue;
            }
            Put put = new Put(bytes(RowKeys.timeSeries(entityId, windowStart)));
            mapper.map(row, put);
            mutator.mutate(put);
        }
        // Closing the mutator flushes, but doing it explicitly means a failure
        // surfaces here rather than inside a suppressed close() exception.
        mutator.flush();
    }

    /** Fills one {@link Put} from one aggregate row. */
    @FunctionalInterface
    private interface RowMapper extends Serializable {
        void map(Row row, Put put);
    }

    // A null aggregate means "nothing to say" — avg() over only nulls, for
    // instance. Writing a zero instead would be a fabricated measurement, and a
    // reader could not tell it apart from a genuine zero.

    private static void putDouble(Put put, byte[] family, String qualifier, Row row, String column) {
        Object value = valueOf(row, column);
        if (value instanceof Number n) {
            put.addColumn(family, bytes(qualifier), Bytes.toBytes(n.doubleValue()));
        }
    }

    private static void putLong(Put put, byte[] family, String qualifier, Row row, String column) {
        Object value = valueOf(row, column);
        if (value instanceof Number n) {
            put.addColumn(family, bytes(qualifier), Bytes.toBytes(n.longValue()));
        }
    }

    private static void putString(Put put, byte[] family, String qualifier, Row row, String column) {
        Object value = valueOf(row, column);
        if (value != null) {
            put.addColumn(family, bytes(qualifier), bytes(value.toString()));
        }
    }

    private static Object valueOf(Row row, String column) {
        int index = row.fieldIndex(column);
        return row.isNullAt(index) ? null : row.get(index);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
