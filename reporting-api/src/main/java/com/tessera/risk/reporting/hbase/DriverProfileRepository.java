package com.tessera.risk.reporting.hbase;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.springframework.stereotype.Repository;

import com.tessera.risk.common.hbase.RiskTables;

/**
 * Reads {@code driver_profile}, which doubles as the fleet roster.
 *
 * <h2>Why a full scan is the right call here</h2>
 * Elsewhere in this service a full scan would be a mistake. This table is the
 * exception: it holds one row per vehicle, so the whole thing is a few dozen rows,
 * and there is no other way to learn which vehicles exist — HBase has no
 * "distinct row key prefix" operation, and the time-series tables hold thousands of
 * rows per vehicle.
 *
 * <p>That is also why the streaming job writes this table at all. It could have
 * been derived by scanning {@code vehicle_risk}, and that scan would grow without
 * bound as history accumulated. A reference table keeps the roster lookup
 * proportional to the fleet rather than to its history.
 */
@Repository
public class DriverProfileRepository {

    private static final byte[] CF_PROFILE = Bytes.toBytes(RiskTables.CF_PROFILE);

    private final Connection connection;

    public DriverProfileRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * Vehicle id to driver display name, in row-key order.
     *
     * <p>Ordered because the row key is the vehicle id, so HBase returns the fleet
     * sorted by identifier without the service sorting anything. A dashboard listing
     * vehicles therefore gets a stable order for free.
     */
    public Map<String, String> driverNamesByVehicle() throws IOException {
        Map<String, String> names = new LinkedHashMap<>();
        try (Table table = connection.getTable(TableName.valueOf(RiskTables.DRIVER_PROFILE));
             ResultScanner scanner = table.getScanner(new Scan().addFamily(CF_PROFILE))) {
            for (Result result : scanner) {
                String vehicleId = Bytes.toString(result.getRow());
                names.put(vehicleId,
                        Cells.getString(result, CF_PROFILE, RiskTables.DRIVER_NAME, vehicleId));
            }
        }
        return names;
    }
}
