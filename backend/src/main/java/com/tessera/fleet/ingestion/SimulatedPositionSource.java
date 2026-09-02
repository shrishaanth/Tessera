package com.tessera.fleet.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.model.PositionReport;
import com.tessera.fleet.routing.GeoMath;
import com.tessera.fleet.routing.RoadGraph;

/**
 * A deterministic vehicle-position feed that moves synthetic vehicles along real
 * OSM road edges. Used for local development and by every automated test, so the
 * suite never depends on an external feed.
 *
 * <p>This is <em>not</em> real position data and is disclosed as such (FR-7.2):
 * {@link #isSubstitute()} is {@code true} and {@link #disclosure()} says plainly
 * that the positions are simulated.
 *
 * <p>Determinism: all randomness comes from a single seeded {@link Random}, and
 * motion is a pure function of the elapsed-time argument to {@link #advance(long)}.
 * {@link #poll()} feeds it wall-clock elapsed time; tests call {@link #advance(long)}
 * directly with fixed steps.
 */
public class SimulatedPositionSource implements PositionSource {

    private static final String[] DRIVER_NAMES = {
            "A. Okafor", "B. Nguyen", "C. Delgado", "D. Petrov", "E. Haddad",
            "F. Larsson", "G. Ibrahim", "H. Kowalski", "I. Santos", "J. Meyer",
            "K. Abebe", "L. Romano", "M. Fernandez", "N. Dubois", "O. Yamamoto",
            "P. Novak", "Q. Rahman", "R. Andersson", "S. Kaur", "T. Bauer",
            "U. Costa", "V. Popescu", "W. Sørensen", "X. Fisher", "Y. Kovac",
            "Z. Moreau", "A. Bianchi", "B. Schmidt", "C. Walsh", "D. Fournier"
    };

    private final RoadGraph graph;
    private final int vehicleCount;
    private final Random random;
    private final List<SimVehicle> vehicles = new ArrayList<>();
    private long lastPollEpochMs = 0L;

    public SimulatedPositionSource(RoadGraph graph, FleetProperties.Simulator config) {
        this.graph = graph;
        this.vehicleCount = Math.max(0, config.vehicleCount());
        this.random = new Random(config.seed());
        spawn();
    }

    private void spawn() {
        for (int i = 0; i < vehicleCount; i++) {
            String id = String.format("SIM-%03d", i + 1);
            String driver = DRIVER_NAMES[i % DRIVER_NAMES.length];
            int node = randomNodeWithOutEdge();
            SimVehicle v = new SimVehicle(id, driver, node);
            pickNextEdge(v, -1);
            vehicles.add(v);
        }
    }

    private int randomNodeWithOutEdge() {
        for (int attempt = 0; attempt < 64; attempt++) {
            int n = random.nextInt(graph.nodeCount());
            if (graph.fwdRangeEnd(n) > graph.fwdRangeStart(n)) {
                return n;
            }
        }
        return 0;
    }

    /**
     * Advance every vehicle by {@code elapsedMillis} of simulated time and return
     * a fresh position report for each. Pure w.r.t. the argument and RNG state.
     */
    public List<PositionReport> advance(long elapsedMillis) {
        double dtSec = Math.max(0, elapsedMillis) / 1000.0;
        long now = System.currentTimeMillis();
        List<PositionReport> reports = new ArrayList<>(vehicles.size());
        for (SimVehicle v : vehicles) {
            step(v, dtSec);
            double f = v.edgeLenM <= 0 ? 0.0 : v.progressM / v.edgeLenM;
            double lat = lerp(graph.lat(v.fromNode), graph.lat(v.toNode), f);
            double lon = lerp(graph.lon(v.fromNode), graph.lon(v.toNode), f);
            double heading = GeoMath.bearingDegrees(
                    graph.lat(v.fromNode), graph.lon(v.fromNode),
                    graph.lat(v.toNode), graph.lon(v.toNode));
            reports.add(new PositionReport(v.id, v.driver, lat, lon,
                    heading, v.edgeSpeedMps * 3.6, now));
        }
        return reports;
    }

    private void step(SimVehicle v, double dtSec) {
        double move = v.edgeSpeedMps * dtSec;
        int guard = 0;
        while (move > 0 && guard++ < 512) {
            double remainingOnEdge = v.edgeLenM - v.progressM;
            if (move < remainingOnEdge) {
                v.progressM += move;
                return;
            }
            move -= remainingOnEdge;
            int arrived = v.toNode;
            int cameFrom = v.fromNode;
            v.fromNode = arrived;
            pickNextEdge(v, cameFrom);
            v.progressM = 0;
            if (v.edgeLenM <= 0) {
                return;
            }
        }
    }

    private void pickNextEdge(SimVehicle v, int avoidNode) {
        int start = graph.fwdRangeStart(v.fromNode);
        int end = graph.fwdRangeEnd(v.fromNode);
        if (end <= start) {
            // Dead end in the directed sense: jump to a live node.
            v.fromNode = randomNodeWithOutEdge();
            start = graph.fwdRangeStart(v.fromNode);
            end = graph.fwdRangeEnd(v.fromNode);
        }
        int chosen = -1;
        int options = end - start;
        int offset = random.nextInt(options);
        for (int k = 0; k < options; k++) {
            int e = start + (offset + k) % options;
            if (graph.fwdTarget(e) != avoidNode) {
                chosen = e;
                break;
            }
        }
        if (chosen < 0) {
            chosen = start + offset; // only way out is back the way we came
        }
        v.toNode = graph.fwdTarget(chosen);
        v.edgeLenM = GeoMath.haversineMeters(
                graph.lat(v.fromNode), graph.lon(v.fromNode),
                graph.lat(v.toNode), graph.lon(v.toNode));
        double travelSec = graph.fwdTravelSec(chosen);
        v.edgeSpeedMps = travelSec > 0.01 ? v.edgeLenM / travelSec : 8.0;
        if (v.edgeSpeedMps < 1.0) {
            v.edgeSpeedMps = 1.0;
        }
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    // ------------------------------------------------------------ PositionSource

    @Override
    public String id() {
        return "simulator";
    }

    @Override
    public String displayName() {
        return "Built-in vehicle simulator";
    }

    @Override
    public boolean isSubstitute() {
        return true;
    }

    @Override
    public String disclosure() {
        return "Positions are SIMULATED, not real. Synthetic vehicles are moved "
                + "along a real OpenStreetMap road network for the demo area. "
                + "No physical vehicles or telematics feed are involved.";
    }

    @Override
    public List<PositionReport> poll() {
        long now = System.currentTimeMillis();
        long elapsed = lastPollEpochMs == 0 ? 1000L : now - lastPollEpochMs;
        lastPollEpochMs = now;
        return advance(elapsed);
    }

    public int vehicleCount() {
        return vehicleCount;
    }

    private static final class SimVehicle {
        final String id;
        final String driver;
        int fromNode;
        int toNode;
        double edgeLenM;
        double edgeSpeedMps;
        double progressM;

        SimVehicle(String id, String driver, int startNode) {
            this.id = id;
            this.driver = driver;
            this.fromNode = startNode;
            this.toNode = startNode;
        }
    }
}
