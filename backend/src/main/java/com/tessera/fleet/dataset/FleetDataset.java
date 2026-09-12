package com.tessera.fleet.dataset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.Resource;

/**
 * A pre-generated fleet trajectory dataset, held in memory.
 *
 * <p>Produced by {@link FleetDatasetGenerator} and shipped as a gzipped NDJSON
 * resource: a header line of metadata, then one line per tick holding
 * {@code [lat, lon, speedKph, headingDeg]} for every vehicle. The file is small
 * enough to keep resident (a 24-vehicle, 1-hour dataset is a few MB of doubles),
 * which keeps replay allocation-free on the ingestion path.
 *
 * <p>Every vehicle starts and ends parked at its depot, so the track can be
 * replayed on an endless loop without a positional discontinuity at the seam.
 */
public final class FleetDataset {

    private final String area;
    private final long tickMillis;
    private final int tickCount;
    private final String[] vehicleIds;
    private final String[] driverNames;
    /** [tick][vehicle * 4 + {0:lat, 1:lon, 2:speedKph, 3:headingDeg}] */
    private final double[][] frames;

    private FleetDataset(String area, long tickMillis, int tickCount,
                         String[] vehicleIds, String[] driverNames, double[][] frames) {
        this.area = area;
        this.tickMillis = tickMillis;
        this.tickCount = tickCount;
        this.vehicleIds = vehicleIds;
        this.driverNames = driverNames;
        this.frames = frames;
    }

    public static FleetDataset load(Resource resource) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream raw = resource.getInputStream();
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     new GZIPInputStream(raw), StandardCharsets.UTF_8))) {

            String headerLine = in.readLine();
            if (headerLine == null) {
                throw new IOException("Empty fleet dataset: " + resource);
            }
            JsonNode header = mapper.readTree(headerLine);
            String area = header.path("area").asText("");
            long tickMillis = header.path("tickMillis").asLong(1000L);
            int tickCount = header.path("tickCount").asInt();
            JsonNode vehicles = header.path("vehicles");
            int n = vehicles.size();
            if (n == 0 || tickCount <= 0) {
                throw new IOException("Fleet dataset declares no vehicles or ticks: " + resource);
            }
            String[] ids = new String[n];
            String[] drivers = new String[n];
            for (int i = 0; i < n; i++) {
                ids[i] = vehicles.get(i).path("id").asText();
                drivers[i] = vehicles.get(i).path("driver").asText(null);
            }

            double[][] frames = new double[tickCount][];
            int t = 0;
            String line;
            while (t < tickCount && (line = in.readLine()) != null) {
                JsonNode row = mapper.readTree(line);
                double[] flat = new double[n * 4];
                for (int v = 0; v < n; v++) {
                    JsonNode s = row.get(v);
                    flat[v * 4] = s.get(0).asDouble();
                    flat[v * 4 + 1] = s.get(1).asDouble();
                    flat[v * 4 + 2] = s.get(2).asDouble();
                    flat[v * 4 + 3] = s.get(3).asDouble();
                }
                frames[t++] = flat;
            }
            if (t != tickCount) {
                throw new IOException("Fleet dataset truncated: expected " + tickCount
                        + " ticks, read " + t);
            }
            return new FleetDataset(area, tickMillis, tickCount, ids, drivers, frames);
        }
    }

    public String area() {
        return area;
    }

    public long tickMillis() {
        return tickMillis;
    }

    public int tickCount() {
        return tickCount;
    }

    public int vehicleCount() {
        return vehicleIds.length;
    }

    public String vehicleId(int index) {
        return vehicleIds[index];
    }

    public String driverName(int index) {
        return driverNames[index];
    }

    public double latitude(int tick, int vehicle) {
        return frames[tick][vehicle * 4];
    }

    public double longitude(int tick, int vehicle) {
        return frames[tick][vehicle * 4 + 1];
    }

    public double speedKph(int tick, int vehicle) {
        return frames[tick][vehicle * 4 + 2];
    }

    public double headingDeg(int tick, int vehicle) {
        return frames[tick][vehicle * 4 + 3];
    }
}
