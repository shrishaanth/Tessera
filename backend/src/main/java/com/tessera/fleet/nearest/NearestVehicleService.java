package com.tessera.fleet.nearest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.live.GeoCandidate;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.NearestVehicle;
import com.tessera.fleet.model.Vehicle;
import com.tessera.fleet.model.VehicleStatus;
import com.tessera.fleet.routing.RoadGraph;
import com.tessera.fleet.routing.TravelTimeService;

/**
 * Nearest-available-vehicle assignment (FR-2).
 *
 * <p>Two stages:
 * <ol>
 *   <li>a Redis GEOSEARCH straight-line prefilter to a candidate set, radius
 *       grown geometrically until enough {@code AVAILABLE} vehicles are in range
 *       or a ceiling is hit;</li>
 *   <li>a single reverse-Dijkstra from the job location over the OSM road graph,
 *       giving each candidate a real road-network drive time — the ranking key
 *       (FR-2.2). Straight-line distance is returned for reference only.</li>
 * </ol>
 * At a 200-vehicle fleet and a few-thousand-edge graph this stays well inside the
 * 1-second budget (FR-2.3 / NFR-1).
 */
@Service
public class NearestVehicleService {

    private final LiveFleetService liveFleet;
    private final TravelTimeService travelTime;
    private final FleetProperties.Nearest config;

    public NearestVehicleService(LiveFleetService liveFleet, TravelTimeService travelTime,
                                 FleetProperties properties) {
        this.liveFleet = liveFleet;
        this.travelTime = travelTime;
        this.config = properties.nearest();
    }

    public List<NearestVehicle> nearestAvailable(double jobLat, double jobLon, int limitOverride) {
        int limit = limitOverride > 0 ? limitOverride : config.shortlistSize();

        List<Vehicle> available = prefilterAvailable(jobLat, jobLon, limit);
        if (available.isEmpty()) {
            return List.of();
        }

        RoadGraph graph = travelTime.graph();
        int jobNode = graph.nearestNode(jobLat, jobLon);
        double[] driveTimeToJob = travelTime.driveTimesToJob(jobNode);
        double jobSnap = travelTime.snapPenaltySec(jobLat, jobLon, jobNode);

        List<NearestVehicle> ranked = new ArrayList<>(available.size());
        for (Vehicle v : available) {
            int vNode = graph.nearestNode(v.latitude(), v.longitude());
            double onGraph = driveTimeToJob[vNode];
            double straight = com.tessera.fleet.routing.GeoMath.haversineMeters(
                    jobLat, jobLon, v.latitude(), v.longitude());
            double travelSeconds;
            if (Double.isInfinite(onGraph)) {
                // No routable path found; fall back to a slow straight-line estimate
                // and let it sort to the bottom.
                travelSeconds = straight / (8.0 / 3.6) + 100_000;
            } else {
                travelSeconds = travelTime.snapPenaltySec(v.latitude(), v.longitude(), vNode)
                        + onGraph + jobSnap;
            }
            ranked.add(new NearestVehicle(v.vehicleId(), v.driverName(),
                    straight, travelSeconds, v.latitude(), v.longitude()));
        }
        ranked.sort(Comparator.comparingDouble(NearestVehicle::travelSeconds));
        return ranked.size() > limit ? new ArrayList<>(ranked.subList(0, limit)) : ranked;
    }

    private List<Vehicle> prefilterAvailable(double lat, double lon, int limit) {
        int radius = Math.max(100, config.prefilterRadiusMeters());
        int ceiling = Math.max(radius, config.maxRadiusMeters());
        int fetch = Math.max(limit * 8, 50);
        List<Vehicle> available = new ArrayList<>();
        while (true) {
            available.clear();
            List<GeoCandidate> candidates = liveFleet.searchNearby(lat, lon, radius, fetch);
            for (GeoCandidate c : candidates) {
                Vehicle v = liveFleet.getVehicle(c.vehicleId());
                if (v != null && v.status() == VehicleStatus.AVAILABLE) {
                    available.add(v);
                }
            }
            if (available.size() >= limit || radius >= ceiling) {
                return available;
            }
            radius = Math.min(radius * 2, ceiling);
        }
    }
}
