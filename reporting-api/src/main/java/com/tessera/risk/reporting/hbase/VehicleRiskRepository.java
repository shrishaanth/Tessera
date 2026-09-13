package com.tessera.risk.reporting.hbase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.tessera.risk.reporting.model.VehicleRisk;

/**
 * Reads {@code vehicle_risk}.
 *
 * <h2>Where the row-key design pays off</h2>
 * Every query this service makes is "what has this vehicle done most recently", and
 * the reversed timestamp in the row key is what makes that cheap. Because
 * {@code Long.MAX_VALUE - windowStart} descends as time advances, a vehicle's newest
 * window is the <em>first</em> row in its key range — so "latest state" is a scan
 * with a limit of one, and "recent history" a scan of a few rows, regardless of how
 * much history has accumulated behind it.
 *
 * <p>With an ascending timestamp the same questions would require reading a
 * vehicle's entire history to reach the end of it, and the cost of the dashboard's
 * main view would grow with the age of the deployment.
 */
@Repository
public class VehicleRiskRepository {

    private static final byte[] CF_BEHAVIOR = Bytes.toBytes(RiskTables.CF_BEHAVIOR);
    private static final byte[] CF_SCORE = Bytes.toBytes(RiskTables.CF_SCORE);

    private final Connection connection;

    public VehicleRiskRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * How many recent windows to consider when choosing the one to display.
     *
     * <p>Slightly more than the five a vehicle is in at once under the default
     * five-minute window sliding by a minute, so the fullest is always among them.
     */
    private static final int CANDIDATE_WINDOWS = 6;

    /**
     * The window that best describes a vehicle now, or empty if it has never
     * reported.
     *
     * <h2>Not simply the newest one</h2>
     * The obvious implementation is a one-row scan, and it produces a dashboard that
     * flickers. Windows slide by a minute, so a vehicle is in five at once, and the
     * newest is the one that has just opened — perhaps ten readings old. Because the
     * risk score is a rate, that window's score is computed over almost no evidence,
     * and it swings between 0 and 100 as windows roll over.
     *
     * <p>Every <em>open</em> window already contains the vehicle's latest reading;
     * they differ only in how far back they reach, so among those the fullest is
     * the same moment seen over a longer run.
     *
     * <h2>But only among open windows</h2>
     * A window that has already ended stops receiving readings, and it is also the
     * fullest of all — so choosing by reading count alone picks it, and the
     * dashboard shows each vehicle where it was up to a minute ago, with markers
     * that jump once a minute instead of moving. Candidates are therefore first
     * narrowed to the windows holding the newest reading, which is exactly the set
     * still being written to, and only then ranked by how much evidence they hold.
     */
    public Optional<VehicleRisk> latest(String vehicleId, Map<String, String> driverNames)
            throws IOException {
        List<VehicleRisk> candidates = history(vehicleId, CANDIDATE_WINDOWS, driverNames);
        long newestReading = candidates.stream()
                .mapToLong(VehicleRisk::lastReadingTs).max().orElse(Long.MIN_VALUE);
        return candidates.stream()
                .filter(window -> window.lastReadingTs() == newestReading)
                .max(Comparator.comparingLong(VehicleRisk::readingCount)
                        // The newer window breaks a tie, which happens when a vehicle
                        // has been reporting for less than one full window.
                        .thenComparingLong(VehicleRisk::windowStart));
    }

    /** The newest {@code limit} windows for one vehicle, newest first. */
    public List<VehicleRisk> history(String vehicleId, int limit, Map<String, String> driverNames)
            throws IOException {
        List<VehicleRisk> out = new ArrayList<>();
        Scan scan = new Scan()
                // Bounded to this vehicle's range. The stop key excludes the next
                // vehicle whose id shares a prefix — VEH-0011 against VEH-001 —
                // which an unbounded scan would silently include.
                .withStartRow(Bytes.toBytes(RowKeys.scanStartForEntity(vehicleId)))
                .withStopRow(Bytes.toBytes(RowKeys.scanStopForEntity(vehicleId)))
                .setLimit(limit)
                // Only the families the response needs. HBase reads families
                // independently, so this is the split in HBaseSchema paying off.
                .addFamily(CF_BEHAVIOR)
                .addFamily(CF_SCORE);

        try (Table table = connection.getTable(TableName.valueOf(RiskTables.VEHICLE_RISK));
             ResultScanner scanner = table.getScanner(scan)) {
            for (Result result : scanner) {
                out.add(toVehicleRisk(result, driverNames));
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private static VehicleRisk toVehicleRisk(Result result, Map<String, String> driverNames) {
        String rowKey = Bytes.toString(result.getRow());
        String vehicleId = RowKeys.entityOf(rowKey);
        long windowStart = RowKeys.windowStartOf(rowKey);

        double riskScore = Cells.getDouble(result, CF_SCORE, RiskTables.RISK_SCORE, 0.0);
        // Null rather than zero: absent means no model has run, which is a different
        // statement from "the model is confident nothing will happen".
        Long predictedLabel = Cells.getNullableLong(result, CF_SCORE, RiskTables.PREDICTED_LABEL);
        Double probability = Cells.getNullableDouble(result, CF_SCORE, RiskTables.PROBABILITY);

        String driverId = Cells.getString(result, CF_BEHAVIOR, RiskTables.DRIVER_ID, "");
        return new VehicleRisk(
                vehicleId,
                driverId,
                driverNames.getOrDefault(vehicleId, driverId),
                Cells.getString(result, CF_BEHAVIOR, RiskTables.RISK_TIER, "AVERAGE"),
                windowStart,
                Cells.getLong(result, CF_BEHAVIOR, RiskTables.WINDOW_END, 0L),
                Cells.getLong(result, CF_BEHAVIOR, RiskTables.READING_COUNT, 0L),
                Cells.getLong(result, CF_BEHAVIOR, RiskTables.HARD_BRAKE_COUNT, 0L),
                Cells.getLong(result, CF_BEHAVIOR, RiskTables.SPEED_VIOLATION_COUNT, 0L),
                Cells.getLong(result, CF_BEHAVIOR, RiskTables.INCIDENT_COUNT, 0L),
                Cells.getDouble(result, CF_BEHAVIOR, RiskTables.AVG_SPEED_KPH, 0.0),
                Cells.getDouble(result, CF_BEHAVIOR, RiskTables.MAX_SPEED_KPH, 0.0),
                Cells.getDouble(result, CF_BEHAVIOR, RiskTables.AVG_OVER_LIMIT_RATIO, 0.0),
                Cells.getDouble(result, CF_BEHAVIOR, RiskTables.MAX_SEVERITY, 0.0),
                Cells.getDouble(result, CF_BEHAVIOR, RiskTables.MOVING_RATIO, 0.0),
                Cells.getDouble(result, CF_BEHAVIOR, RiskTables.LAT, 0.0),
                Cells.getDouble(result, CF_BEHAVIOR, RiskTables.LON, 0.0),
                Cells.getDouble(result, CF_BEHAVIOR, RiskTables.HEADING_DEG, 0.0),
                Cells.getLong(result, CF_BEHAVIOR, RiskTables.LAST_READING_TS, 0L),
                riskScore,
                RiskLevel.of(riskScore),
                predictedLabel,
                probability,
                predictedLabel != null);
    }
}
