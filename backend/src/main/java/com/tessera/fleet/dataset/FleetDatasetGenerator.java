package com.tessera.fleet.dataset;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.tessera.fleet.routing.GeoMath;
import com.tessera.fleet.routing.RoadGraph;
import com.tessera.fleet.routing.RoadGraphLoader;

/**
 * Offline generator for the synthetic fleet trajectory dataset.
 *
 * <p>The output is <em>simulated</em> data, but it is generated to be physically
 * plausible rather than merely random — the earlier live simulator produced
 * drunkard's-walk motion with teleports, which is exactly what this replaces.
 * Every guarantee below holds <em>by construction</em>, and
 * {@code FleetDatasetPlausibilityTest} re-checks them against the shipped file:
 *
 * <ul>
 *   <li><b>Continuous path.</b> A vehicle's position is always a point on a real
 *       OSM road edge; it only ever moves to an adjacent edge on its planned
 *       route. There is no mechanism that can relocate it.</li>
 *   <li><b>Purposeful routing.</b> Each vehicle drives a closed tour — depot →
 *       several stops → depot — along shortest paths, not a random walk.</li>
 *   <li><b>Valid speed profile.</b> Per leg, a speed cap is built from each
 *       edge's real speed limit and a corner limit at each node, then smoothed
 *       by a backward pass so the vehicle can always brake in time, then
 *       integrated forward under an acceleration limit. Speed is therefore
 *       continuous and never exceeds what the road allows.</li>
 *   <li><b>Realistic stops.</b> Vehicles hold at signalised intersections and
 *       dwell at customer stops, which is also what makes geofence dwell data
 *       meaningful.</li>
 *   <li><b>Seamless looping.</b> Every vehicle starts and ends the dataset
 *       parked at its depot at zero speed, so replaying on a loop introduces no
 *       discontinuity.</li>
 * </ul>
 *
 * <p>Run it (after {@code mvn -f backend/pom.xml compile}):
 * <pre>
 * java -cp backend/target/classes:$(deps) com.tessera.fleet.dataset.FleetDatasetGenerator \
 *     --graph backend/src/main/resources/roadgraph/roadgraph.json \
 *     --out   backend/src/main/resources/dataset/fleet-round.ndjson.gz
 * </pre>
 */
public final class FleetDatasetGenerator {

    /** Hard ceiling on speed; also bounds per-tick displacement (25 m/s = 90 km/h). */
    public static final double V_MAX_MPS = 25.0;
    /** Comfortable acceleration limit. */
    public static final double A_MAX = 1.3;
    /** Comfortable braking limit; also used for the backward feasibility pass. */
    public static final double B_MAX = 2.4;
    /** Speed cap through a sharp turn / a gentle turn. */
    private static final double V_TURN_SHARP = 3.5;
    private static final double V_TURN_MED = 7.5;

    private static final String[] DRIVER_NAMES = {
            "A. Okafor", "B. Nguyen", "C. Delgado", "D. Petrov", "E. Haddad",
            "F. Larsson", "G. Ibrahim", "H. Kowalski", "I. Santos", "J. Meyer",
            "K. Abebe", "L. Romano", "M. Fernandez", "N. Dubois", "O. Yamamoto",
            "P. Novak", "Q. Rahman", "R. Andersson", "S. Kaur", "T. Bauer",
            "U. Costa", "V. Popescu", "W. Sørensen", "X. Fisher", "Y. Kovac",
            "Z. Moreau", "A. Bianchi", "B. Schmidt", "C. Walsh", "D. Fournier"
    };

    private FleetDatasetGenerator() { }

    public static void main(String[] args) throws Exception {
        String graphPath = arg(args, "--graph", "backend/src/main/resources/roadgraph/roadgraph.json");
        String outPath = arg(args, "--out", "backend/src/main/resources/dataset/fleet-round.ndjson.gz");
        int vehicles = Integer.parseInt(arg(args, "--vehicles", "24"));
        int ticks = Integer.parseInt(arg(args, "--ticks", "3600"));
        long seed = Long.parseLong(arg(args, "--seed", "20260909"));

        ObjectMapper mapper = new ObjectMapper();
        RoadGraph graph = RoadGraphLoader.parse(mapper.readTree(Files.readAllBytes(Path.of(graphPath))));
        System.out.printf("Road graph: %s — %d nodes, %d edges%n",
                graph.areaName(), graph.nodeCount(), graph.edgeCount());

        // Preferred stops — the demo customer sites — so vehicles actually dwell
        // inside the geofences and produce real ENTER/EXIT and dwell data.
        double[][] stops = parseStops(arg(args, "--stops",
                "42.35560,-71.06030;42.36585,-71.06110;42.35610,-71.06350"));

        double[][][] frames = simulate(graph, vehicles, ticks, seed, stops);

        validate(frames, vehicles, ticks);

        Path out = Path.of(outPath);
        Files.createDirectories(out.getParent());
        write(out, graph, vehicles, ticks, seed, frames);
        System.out.printf("Wrote %s — %d vehicles × %d ticks (%.1f MB)%n",
                out, vehicles, ticks, Files.size(out) / 1e6);
    }

    // ------------------------------------------------------------------ model

    /** One vehicle's plan: a per-tick position/speed/heading track. */
    private static double[][][] simulate(RoadGraph g, int vehicleCount, int ticks, long seed,
                                         double[][] stopCoords) {
        int[] drivable = drivableNodes(g);
        // The largest set of nodes that are mutually reachable with the busiest
        // hub. Restricting depots and stops to it means every leg — including the
        // return to the depot — is guaranteed routable, so a round can always be
        // closed and no fallback that could teleport is ever needed.
        int hub = busiestNode(g, drivable);
        int[] pool = roundTripPool(g, hub);
        System.out.printf("Round-trip routable pool: %d of %d drivable nodes%n",
                pool.length, drivable.length);

        int[] preferred = new int[stopCoords.length];
        for (int i = 0; i < stopCoords.length; i++) {
            preferred[i] = nearestInPool(g, pool, stopCoords[i][0], stopCoords[i][1]);
        }

        double[][][] frames = new double[ticks][vehicleCount][4];
        for (int v = 0; v < vehicleCount; v++) {
            Random vr = new Random(seed * 31 + v);
            int depot = pool[vr.nextInt(pool.length)];
            List<double[]> track = driveTour(g, depot, pool, preferred, vr, ticks);
            for (int t = 0; t < ticks; t++) {
                frames[t][v] = track.get(t);
            }
            if (v % 4 == 0) {
                System.out.printf("  vehicle %d/%d planned%n", v + 1, vehicleCount);
            }
        }
        return frames;
    }

    /**
     * Back-to-back closed rounds: depot → a few stops → depot, repeated until the
     * dataset is full, then parked at the depot for any remainder.
     *
     * <p>A round is simulated into a scratch list first and only appended if it
     * fits in the remaining ticks. That matters: truncating a round mid-route and
     * then padding at the depot is exactly what would introduce a teleport, and
     * it also guarantees the vehicle is parked at the depot on the final tick so
     * the loop seam stays continuous.
     */
    private static List<double[]> driveTour(RoadGraph g, int depot, int[] pool,
                                            int[] preferredStops, Random rnd, int maxTicks) {
        List<double[]> track = new ArrayList<>(maxTicks);
        park(track, g, depot, rnd.nextInt(45)); // short staggered start

        while (track.size() < maxTicks) {
            List<double[]> round = new ArrayList<>();
            int here = depot;
            int stops = 2 + rnd.nextInt(3);
            for (int i = 0; i < stops; i++) {
                boolean preferred = preferredStops.length > 0 && rnd.nextDouble() < 0.45;
                int wp = preferred
                        ? preferredStops[rnd.nextInt(preferredStops.length)]
                        : pool[rnd.nextInt(pool.length)];
                int[] route = shortestPath(g, here, wp);
                if (route == null || route.length < 2) {
                    continue;
                }
                driveLeg(g, route, rnd, round);
                here = wp;
                // Dwell: longer at a customer stop so geofence dwell data is real.
                park(round, g, here, preferred ? 60 + rnd.nextInt(180) : 15 + rnd.nextInt(45));
            }
            int[] back = shortestPath(g, here, depot);
            if (back != null && back.length >= 2) {
                driveLeg(g, back, rnd, round);
            } else if (here != depot) {
                break; // cannot close the round; stop here rather than teleport
            }
            int turnaround = 10 + rnd.nextInt(40);
            if (track.size() + round.size() + turnaround > maxTicks) {
                break; // would overshoot — leave the rest parked at the depot
            }
            track.addAll(round);
            park(track, g, depot, turnaround);
        }

        // Pad at the depot. Safe: the loop only ever appends whole rounds, so the
        // vehicle is standing at the depot right now.
        while (track.size() < maxTicks) {
            track.add(new double[] {g.lat(depot), g.lon(depot), 0, 0});
        }
        return track;
    }

    private static void park(List<double[]> track, RoadGraph g, int node, int ticks) {
        double lat = g.lat(node);
        double lon = g.lon(node);
        double heading = track.isEmpty() ? 0 : track.get(track.size() - 1)[3];
        for (int i = 0; i < ticks; i++) {
            track.add(new double[] {lat, lon, 0, heading});
        }
    }

    /**
     * Drive one shortest-path leg with a physically valid speed profile.
     *
     * <p>Three passes: build a per-node speed cap from road limits, corner
     * geometry and traffic-signal stops; smooth it backwards so braking is
     * always feasible; then integrate forwards under the acceleration limit,
     * sampling position every second.
     */
    private static void driveLeg(RoadGraph g, int[] route, Random rnd, List<double[]> track) {
        int n = route.length;
        double[] segLen = new double[n - 1];
        double[] segLimit = new double[n - 1];
        double[] segBearing = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            int a = route[i];
            int b = route[i + 1];
            segLen[i] = Math.max(0.1, GeoMath.haversineMeters(g.lat(a), g.lon(a), g.lat(b), g.lon(b)));
            segLimit[i] = Math.min(V_MAX_MPS, edgeSpeedMps(g, a, b, segLen[i]));
            segBearing[i] = GeoMath.bearingDegrees(g.lat(a), g.lon(a), g.lat(b), g.lon(b));
        }

        // Node speed caps: stop at both ends; corner + red-light limits between.
        double[] nodeCap = new double[n];
        nodeCap[0] = 0;
        nodeCap[n - 1] = 0;
        for (int i = 1; i < n - 1; i++) {
            double turn = Math.abs(angleDelta(segBearing[i - 1], segBearing[i]));
            double cap = turn > 55 ? V_TURN_SHARP : turn > 28 ? V_TURN_MED : Double.MAX_VALUE;
            if (isSignalised(g, route[i]) && rnd.nextDouble() < 0.35) {
                cap = 0; // red light
            }
            nodeCap[i] = Math.min(cap, Math.min(segLimit[i - 1], segLimit[i]));
        }

        // Backward pass: guarantee the vehicle can always decelerate to the next cap.
        for (int i = n - 2; i >= 0; i--) {
            double reachable = Math.sqrt(nodeCap[i + 1] * nodeCap[i + 1] + 2 * B_MAX * segLen[i]);
            nodeCap[i] = Math.min(nodeCap[i], Math.min(reachable, segLimit[i]));
        }

        // Forward integration. A fixed sub-step (not "advance to the next node")
        // is what keeps acceleration honest: these OSM edges have a ~6 m median
        // length, so a moving vehicle crosses two or three of them per second, and
        // integrating per-edge would apply the acceleration limit once per
        // crossing and stack it well beyond what a vehicle can actually do.
        final int subSteps = 10;
        final double dt = 1.0 / subSteps;
        double v = 0;
        double emitted = 0; // speed of the most recent sample actually written
        int seg = 0;
        double along = 0;
        int guard = 0;

        while (seg < n - 1 && guard++ < 200_000) {
            int holdTicks = 0;
            for (int k = 0; k < subSteps && seg < n - 1; k++) {
                double toNode = Math.max(0, segLen[seg] - along);
                double vTarget = Math.min(segLimit[seg], Math.sqrt(
                        nodeCap[seg + 1] * nodeCap[seg + 1] + 2 * B_MAX * toNode));
                v = v < vTarget
                        ? Math.min(vTarget, v + A_MAX * dt)
                        : Math.max(vTarget, v - B_MAX * dt);
                v = Math.max(0, Math.min(V_MAX_MPS, v));

                along += v * dt;
                while (seg < n - 1 && along >= segLen[seg]) {
                    along -= segLen[seg];
                    seg++;
                    if (seg < n - 1 && nodeCap[seg] == 0 && v <= 1.0) {
                        // Hold at a red light. Only once the profile has already
                        // brought the vehicle to a crawl — snapping a moving
                        // vehicle to zero would be an implausible deceleration.
                        v = 0;
                        along = 0;
                        holdTicks = 8 + rnd.nextInt(30);
                        k = subSteps; // finish this tick
                    }
                }
            }
            if (seg < n - 1) {
                track.add(sample(g, route, seg, Math.min(1, along / segLen[seg]), v, segBearing[seg]));
                emitted = v;
                for (int h = 0; h < holdTicks; h++) {
                    track.add(sample(g, route, seg, 0, 0, segBearing[Math.max(0, seg - 1)]));
                    emitted = 0;
                }
            }
        }
        // Land exactly on the destination node, so a following dwell (or the next
        // leg, which starts there) is positionally continuous. Ramp down from the
        // last *emitted* speed — the final tick of the loop may not have produced
        // a sample, and snapping straight to zero from the value before it would
        // be a two-tick deceleration crammed into one.
        int last = route[n - 1];
        while (emitted > B_MAX) {
            emitted = Math.max(0, emitted - B_MAX);
            track.add(new double[] {g.lat(last), g.lon(last), emitted * 3.6, segBearing[n - 2]});
        }
        track.add(new double[] {g.lat(last), g.lon(last), 0, segBearing[n - 2]});
    }

    private static double[] sample(RoadGraph g, int[] route, int seg, double f,
                                   double vMps, double bearing) {
        int a = route[seg];
        int b = route[seg + 1];
        return new double[] {
                g.lat(a) + (g.lat(b) - g.lat(a)) * f,
                g.lon(a) + (g.lon(b) - g.lon(a)) * f,
                vMps * 3.6,
                bearing,
        };
    }

    private static double edgeSpeedMps(RoadGraph g, int from, int to, double lenM) {
        for (int e = g.fwdRangeStart(from); e < g.fwdRangeEnd(from); e++) {
            if (g.fwdTarget(e) == to) {
                double sec = g.fwdTravelSec(e);
                if (sec > 0.05) {
                    return Math.max(2.0, lenM / sec);
                }
            }
        }
        return 11.0; // ~40 km/h fallback
    }

    /** Deterministic pseudo-signal at busier intersections. */
    private static boolean isSignalised(RoadGraph g, int node) {
        int degree = g.fwdRangeEnd(node) - g.fwdRangeStart(node);
        return degree >= 3 && (Long.hashCode(g.osmId(node)) & 7) == 0;
    }

    private static double angleDelta(double a, double b) {
        double d = (b - a + 540) % 360 - 180;
        return d;
    }

    // -------------------------------------------------------------- graph ops

    private static int[] drivableNodes(RoadGraph g) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < g.nodeCount(); i++) {
            if (g.fwdRangeEnd(i) - g.fwdRangeStart(i) >= 1) {
                out.add(i);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int busiestNode(RoadGraph g, int[] drivable) {
        int best = drivable[0];
        int bestDegree = -1;
        for (int n : drivable) {
            int degree = (g.fwdRangeEnd(n) - g.fwdRangeStart(n))
                    + (g.revRangeEnd(n) - g.revRangeStart(n));
            if (degree > bestDegree) {
                bestDegree = degree;
                best = n;
            }
        }
        return best;
    }

    /**
     * Nodes that are reachable from {@code hub} <em>and</em> from which
     * {@code hub} is reachable. Any two members are therefore mutually routable
     * (via the hub), which is what makes every tour leg guaranteed-closable.
     */
    private static int[] roundTripPool(RoadGraph g, int hub) {
        boolean[] out = reachable(g, hub, true);
        boolean[] in = reachable(g, hub, false);
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < g.nodeCount(); i++) {
            if (out[i] && in[i] && g.fwdRangeEnd(i) > g.fwdRangeStart(i)) {
                pool.add(i);
            }
        }
        return pool.stream().mapToInt(Integer::intValue).toArray();
    }

    /** BFS over forward edges ({@code forward}) or reverse edges (otherwise). */
    private static boolean[] reachable(RoadGraph g, int from, boolean forward) {
        boolean[] seen = new boolean[g.nodeCount()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen[from] = true;
        queue.add(from);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            int start = forward ? g.fwdRangeStart(u) : g.revRangeStart(u);
            int end = forward ? g.fwdRangeEnd(u) : g.revRangeEnd(u);
            for (int e = start; e < end; e++) {
                int w = forward ? g.fwdTarget(e) : g.revSource(e);
                if (!seen[w]) {
                    seen[w] = true;
                    queue.add(w);
                }
            }
        }
        return seen;
    }

    private static int nearestInPool(RoadGraph g, int[] pool, double lat, double lon) {
        int best = pool[0];
        double bestD = Double.MAX_VALUE;
        for (int n : pool) {
            double d = GeoMath.haversineMeters(lat, lon, g.lat(n), g.lon(n));
            if (d < bestD) {
                bestD = d;
                best = n;
            }
        }
        return best;
    }

    /** Shortest forward path by travel time, as a node sequence, or null. */
    private static int[] shortestPath(RoadGraph g, int src, int dst) {
        if (src == dst) {
            return new int[] {src};
        }
        int n = g.nodeCount();
        double[] dist = new double[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        dist[src] = 0;
        PriorityQueue<long[]> pq = new PriorityQueue<>((x, y) ->
                Double.compare(Double.longBitsToDouble(x[0]), Double.longBitsToDouble(y[0])));
        pq.add(new long[] {Double.doubleToLongBits(0), src});
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            double d = Double.longBitsToDouble(top[0]);
            int u = (int) top[1];
            if (d > dist[u]) {
                continue;
            }
            if (u == dst) {
                break;
            }
            for (int e = g.fwdRangeStart(u); e < g.fwdRangeEnd(u); e++) {
                int w = g.fwdTarget(e);
                double nd = d + Math.max(0.05, g.fwdTravelSec(e));
                if (nd < dist[w]) {
                    dist[w] = nd;
                    prev[w] = u;
                    pq.add(new long[] {Double.doubleToLongBits(nd), w});
                }
            }
        }
        if (!Double.isFinite(dist[dst])) {
            return null;
        }
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int at = dst; at != -1; at = prev[at]) {
            stack.push(at);
        }
        int[] path = new int[stack.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = stack.pop();
        }
        return path;
    }

    // -------------------------------------------------------------- validation

    /**
     * Refuse to ship a dataset that is not physically plausible. The generator is
     * built so these hold by construction; checking them here means a regression
     * in the model fails loudly at generation time instead of quietly producing
     * another teleporting fleet. {@code FleetDatasetPlausibilityTest} re-runs the
     * same checks against the committed file.
     */
    private static void validate(double[][][] frames, int vehicleCount, int ticks) {
        double maxJump = 0;
        double maxAccel = 0;
        double maxSpeed = 0;
        double maxSeam = 0;
        int moving = 0;
        int samples = 0;

        for (int t = 1; t < ticks; t++) {
            for (int v = 0; v < vehicleCount; v++) {
                double[] a = frames[t - 1][v];
                double[] b = frames[t][v];
                if (!Double.isFinite(b[0]) || !Double.isFinite(b[1])) {
                    throw new IllegalStateException("Non-finite position at tick " + t + " vehicle " + v);
                }
                double jump = GeoMath.haversineMeters(a[0], a[1], b[0], b[1]);
                maxJump = Math.max(maxJump, jump);
                maxAccel = Math.max(maxAccel, Math.abs(b[2] - a[2]) / 3.6);
                maxSpeed = Math.max(maxSpeed, b[2]);
                samples++;
                if (jump > 0.5) {
                    moving++;
                }
            }
        }
        for (int v = 0; v < vehicleCount; v++) {
            maxSeam = Math.max(maxSeam, GeoMath.haversineMeters(
                    frames[ticks - 1][v][0], frames[ticks - 1][v][1],
                    frames[0][v][0], frames[0][v][1]));
        }

        double jumpLimit = V_MAX_MPS * 1.05;
        double accelLimit = Math.max(A_MAX, B_MAX) * 1.5;
        System.out.printf("Validation: max jump %.2f m (limit %.1f), max accel %.2f m/s^2 "
                        + "(limit %.1f), max speed %.1f km/h, moving %.1f%%, loop seam %.3f m%n",
                maxJump, jumpLimit, maxAccel, accelLimit, maxSpeed,
                100.0 * moving / samples, maxSeam);

        require(maxJump <= jumpLimit, "per-tick displacement " + maxJump + " m exceeds " + jumpLimit);
        require(maxAccel <= accelLimit, "acceleration " + maxAccel + " m/s^2 exceeds " + accelLimit);
        require(maxSpeed <= V_MAX_MPS * 3.6 * 1.02, "speed " + maxSpeed + " km/h exceeds the cap");
        require(maxSeam <= 1.0, "loop seam discontinuity of " + maxSeam + " m");
        require(moving * 100.0 / samples >= 25.0, "only "
                + (moving * 100.0 / samples) + "% of ticks are moving — the map would look static");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Generated dataset is not plausible: " + message);
        }
    }

    // ------------------------------------------------------------------ output

    private static void write(Path out, RoadGraph g, int vehicleCount, int ticks,
                              long seed, double[][][] frames) throws Exception {
        StringBuilder header = new StringBuilder();
        header.append("{\"format\":\"tessera-fleet-dataset/1\",\"area\":\"")
                .append(g.areaName().replace("\"", "'"))
                .append("\",\"tickMillis\":1000,\"tickCount\":").append(ticks)
                .append(",\"seed\":").append(seed)
                .append(",\"generatedAt\":\"").append(Instant.now()).append("\"")
                .append(",\"vehicles\":[");
        for (int v = 0; v < vehicleCount; v++) {
            if (v > 0) {
                header.append(',');
            }
            header.append("{\"id\":\"").append(String.format("SIM-%03d", v + 1))
                    .append("\",\"driver\":\"")
                    .append(DRIVER_NAMES[v % DRIVER_NAMES.length])
                    .append("\"}");
        }
        header.append("]}");

        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8))) {
            w.write(header.toString());
            w.write('\n');
            StringBuilder line = new StringBuilder(vehicleCount * 34);
            for (int t = 0; t < ticks; t++) {
                line.setLength(0);
                line.append('[');
                for (int v = 0; v < vehicleCount; v++) {
                    double[] s = frames[t][v];
                    if (v > 0) {
                        line.append(',');
                    }
                    line.append('[').append(round(s[0], 6)).append(',').append(round(s[1], 6))
                            .append(',').append(round(s[2], 1)).append(',')
                            .append(round(s[3], 0)).append(']');
                }
                line.append(']');
                w.write(line.toString());
                w.write('\n');
            }
        }
    }

    private static String round(double v, int places) {
        java.math.BigDecimal b = java.math.BigDecimal.valueOf(v)
                .setScale(places, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return b.toPlainString();
    }

    private static double[][] parseStops(String spec) {
        if (spec == null || spec.isBlank()) {
            return new double[0][];
        }
        String[] parts = spec.split(";");
        double[][] out = new double[parts.length][2];
        for (int i = 0; i < parts.length; i++) {
            String[] ll = parts[i].split(",");
            out[i][0] = Double.parseDouble(ll[0].trim());
            out[i][1] = Double.parseDouble(ll[1].trim());
        }
        return out;
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
