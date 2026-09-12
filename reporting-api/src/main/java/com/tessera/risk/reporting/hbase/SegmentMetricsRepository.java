package com.tessera.risk.reporting.hbase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.springframework.stereotype.Repository;

import com.tessera.risk.common.hbase.RiskTables;
import com.tessera.risk.common.hbase.RowKeys;
import com.tessera.risk.reporting.model.RiskLevel;
import com.tessera.risk.reporting.model.SegmentRisk;

/**
 * Reads {@code segment_metrics}.
 *
 * <h2>What this deliberately does not offer</h2>
 * There is no "riskiest segments right now" endpoint, and that is a consequence of
 * the storage model rather than an oversight. Answering it means finding the latest
 * window for each of several thousand segments, and HBase offers no aggregation and
 * no secondary index — so it would be a full-table scan on the dashboard's critical
 * path, growing with retained history.
 *
 * <p>Serving it properly would need either a materialised "current segment risk"
 * table maintained by the streaming job, or an analytical query over the Parquet
 * archive (FR-5.4), which is the right tool for a question ranging over everything.
 * Per-segment lookup is what a key-value store is good at, so that is what this
 * exposes.
 */
@Repository
public class SegmentMetricsRepository {

    private static final byte[] CF_TRAFFIC = Bytes.toBytes(RiskTables.CF_TRAFFIC);
    private static final byte[] CF_RISK = Bytes.toBytes(RiskTables.CF_RISK);

    private final Connection connection;

    public SegmentMetricsRepository(Connection connection) {
        this.connection = connection;
    }

    /** The newest {@code limit} windows for one segment, newest first. */
    public List<SegmentRisk> history(String segmentId, int limit) throws IOException {
        List<SegmentRisk> out = new ArrayList<>();
        Scan scan = new Scan()
                .withStartRow(Bytes.toBytes(RowKeys.scanStartForEntity(segmentId)))
                .withStopRow(Bytes.toBytes(RowKeys.scanStopForEntity(segmentId)))
                .setLimit(limit)
                .addFamily(CF_TRAFFIC)
                .addFamily(CF_RISK);

        try (Table table = connection.getTable(TableName.valueOf(RiskTables.SEGMENT_METRICS));
             ResultScanner scanner = table.getScanner(scan)) {
            for (Result result : scanner) {
                out.add(toSegmentRisk(result));
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private static SegmentRisk toSegmentRisk(Result result) {
        String rowKey = Bytes.toString(result.getRow());
        double riskIndex = Cells.getDouble(result, CF_RISK, RiskTables.RISK_INDEX, 0.0);
        return new SegmentRisk(
                RowKeys.entityOf(rowKey),
                RowKeys.windowStartOf(rowKey),
                Cells.getLong(result, CF_TRAFFIC, RiskTables.WINDOW_END, 0L),
                Cells.getLong(result, CF_TRAFFIC, RiskTables.READING_COUNT, 0L),
                Cells.getLong(result, CF_TRAFFIC, RiskTables.VEHICLE_COUNT, 0L),
                Cells.getDouble(result, CF_TRAFFIC, RiskTables.AVG_SPEED_KPH, 0.0),
                Cells.getDouble(result, CF_TRAFFIC, RiskTables.SPEED_LIMIT_KPH, 0.0),
                Cells.getDouble(result, CF_TRAFFIC, RiskTables.COMPLIANCE_RATIO, 1.0),
                Cells.getLong(result, CF_RISK, RiskTables.HARD_BRAKE_COUNT, 0L),
                Cells.getLong(result, CF_RISK, RiskTables.SPEED_VIOLATION_COUNT, 0L),
                Cells.getLong(result, CF_RISK, RiskTables.INCIDENT_COUNT, 0L),
                riskIndex,
                RiskLevel.of(riskIndex));
    }
}
