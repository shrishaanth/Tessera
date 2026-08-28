package com.geotracker.web;

import com.geotracker.geofence.GeofenceEngine;
import com.geotracker.geofence.GeofenceEngine.UserZone;
import com.geotracker.geofence.GeofenceEngine.Zone;
import com.geotracker.geofence.RayCaster;
import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.index.IndexerThread;
import com.geotracker.index.SpatialSnapshot;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.RouteResult;
import com.geotracker.model.SearchRequest;
import com.geotracker.model.VehicleDetail;
import com.geotracker.model.ZoneEvent;
import com.geotracker.model.ZoneRequest;
import com.geotracker.routing.AStarRouter;
import com.geotracker.routing.GeoUtils;
import com.geotracker.routing.RoadGraph;
import com.geotracker.util.Config;
import com.geotracker.util.RingBuffer;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class WebServer {
    private final Javalin app;
    private final ScheduledExecutorService scheduler;
    private final ObjectMapper mapper;
    private final List<IndexerThread> indexers;
    private final AStarRouter router;
    private final RoadGraph graph;
    private final List<Zone> zones;
    private final GeofenceEngine geofenceEngine;
    private final List<WsContext> wsClients = new CopyOnWriteArrayList<>();
    private final List<WsContext> eventClients = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, RingBuffer<Position>> positionHistory = new ConcurrentHashMap<>();

    public WebServer(List<IndexerThread> indexers, AStarRouter router, RoadGraph graph, List<Zone> zones, GeofenceEngine geofenceEngine) {
        this.indexers = indexers;
        this.router = router;
        this.graph = graph;
        this.zones = zones;
        this.geofenceEngine = geofenceEngine;
        this.mapper = new ObjectMapper();
        this.app = Javalin.create().start(Config.WEB_PORT);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        configureRoutes();
        startPositionBroadcast();
    }

    private void configureRoutes() {
        app.ws("/ws/positions", ws -> {
            ws.onConnect(ctx -> wsClients.add(ctx));
            ws.onClose(ctx -> wsClients.remove(ctx));
            ws.onError(ctx -> wsClients.remove(ctx));
        });

        app.ws("/ws/events", ws -> {
            ws.onConnect(ctx -> eventClients.add(ctx));
            ws.onClose(ctx -> eventClients.remove(ctx));
            ws.onError(ctx -> eventClients.remove(ctx));
        });

        app.get("/api/geofences", ctx -> {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Zone zone : zones) {
                result.add(zoneToMap(zone.id(), zone.polygon(), zone.bbox()));
            }
            for (UserZone zone : geofenceEngine.getAllUserZones()) {
                result.add(zoneToMap(zone.zoneId(), zone.polygon(), zone.bbox()));
            }
            ctx.json(result);
        });

        app.post("/api/zones", ctx -> {
            ZoneRequest req = ctx.bodyAsClass(ZoneRequest.class);
            String zoneId = geofenceEngine.createZone(req.name(), req.polygon(), req.vehicleIds(), req.alertOnEnter(), req.alertOnExit());
            ctx.json(Map.of("zoneId", zoneId));
        });

        app.delete("/api/zones/{id}", ctx -> {
            geofenceEngine.deleteZone(ctx.pathParam("id"));
            ctx.status(204);
        });

        app.put("/api/zones/{id}", ctx -> {
            String zoneId = ctx.pathParam("id");
            ZoneRequest req = ctx.bodyAsClass(ZoneRequest.class);
            geofenceEngine.updateZone(zoneId, req.name(), req.polygon(), req.vehicleIds());
            ctx.status(200);
        });

        app.patch("/api/zones/{id}/vehicles", ctx -> {
            String zoneId = ctx.pathParam("id");
            var body = ctx.bodyAsClass(Map.class);
            @SuppressWarnings("unchecked")
            Set<Long> vehicleIds = new HashSet<>((List<Long>) body.getOrDefault("vehicleIds", List.of()));
            UserZone zone = geofenceEngine.getUserZone(zoneId);
            if (zone == null) {
                ctx.status(404).result("Zone not found");
                return;
            }
            geofenceEngine.updateZone(zoneId, zone.name(), zone.polygon(), vehicleIds);
            ctx.status(200);
        });

        app.post("/api/vehicles/search", ctx -> {
            SearchRequest req = ctx.bodyAsClass(SearchRequest.class);
            BoundingBox bbox = req.bbox();
            Set<Long> filterIds = req.vehicleIds();
            List<Map<String, Object>> results = new ArrayList<>();
            for (IndexerThread indexer : indexers) {
                SpatialSnapshot snapshot = indexer.getPublishedSnapshot();
                if (snapshot == null) continue;
                List<Long> ids = snapshot.quadtree().rangeQuery(bbox);
                for (Long id : ids) {
                    if (!filterIds.isEmpty() && !filterIds.contains(id)) continue;
                    Position pos = snapshot.hamt().get(id);
                    if (pos == null) continue;
                    results.add(positionToMap(id, pos));
                }
            }
            ctx.json(results);
        });

        app.get("/api/route", ctx -> {
            String vehicleIdStr = ctx.queryParam("vehicleId");
            String destXStr = ctx.queryParam("destX");
            String destYStr = ctx.queryParam("destY");
            if (vehicleIdStr == null || destXStr == null || destYStr == null) {
                ctx.status(400).result("Missing required parameters: vehicleId, destX, destY");
                return;
            }
            try {
                long vehicleId = Long.parseLong(vehicleIdStr);
                double destX = Double.parseDouble(destXStr);
                double destY = Double.parseDouble(destYStr);

                Position startPos = null;
                for (IndexerThread indexer : indexers) {
                    SpatialSnapshot snapshot = indexer.getPublishedSnapshot();
                    if (snapshot != null) {
                        startPos = snapshot.hamt().get(vehicleId);
                        if (startPos != null) break;
                    }
                }
                if (startPos == null) {
                    ctx.status(404).result("Vehicle not found");
                    return;
                }

                RoadGraph.Node startNode = graph.findNearestNode(startPos.x(), startPos.y());
                RoadGraph.Node destNode = graph.findNearestNode(destX, destY);
                if (startNode == null || destNode == null) {
                    ctx.status(400).result("Start or destination is not near any road");
                    return;
                }
                if (startNode.id() == destNode.id()) {
                    ctx.status(400).result("Vehicle is already at destination");
                    return;
                }

                RouteResult routeResult = router.findRoute(vehicleId, startNode.id(), destNode.id());
                List<Map<String, Object>> nodes = new ArrayList<>();
                for (Long nodeId : routeResult.nodeIds()) {
                    RoadGraph.Node node = graph.getNode(nodeId);
                    if (node == null) continue;
                    Map<String, Object> nodeMap = new LinkedHashMap<>();
                    nodeMap.put("id", node.id());
                    nodeMap.put("x", node.x());
                    nodeMap.put("y", node.y());
                    nodes.add(nodeMap);
                }

                double speedKmh = 5.0;
                RingBuffer<Position> history = positionHistory.computeIfAbsent(vehicleId, k -> new RingBuffer<>(5));
                if (history.size() >= 2) {
                    Position newest = history.get(history.size() - 1);
                    Position oldest = history.get(0);
                    double dtSec = (newest.timestamp() - oldest.timestamp()) / 1000.0;
                    if (dtSec > 0) {
                        double distM = GeoUtils.haversineMeters(oldest.y(), oldest.x(), newest.y(), newest.x());
                        speedKmh = (distM / dtSec) * 3.6;
                        if (speedKmh < 0.5) speedKmh = 5.0;
                    }
                }
                double speedMps = speedKmh / 3.6;
                double estimatedSeconds = speedMps > 0.1 ? routeResult.totalCost() / speedMps : routeResult.totalCost() / 5.0;

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("vehicleId", routeResult.vehicleId());
                response.put("totalCost", routeResult.totalCost());
                response.put("distanceMeters", routeResult.totalCost());
                response.put("estimatedSeconds", estimatedSeconds);
                response.put("nodes", nodes);
                ctx.json(response);
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid parameter format");
            }
        });

        app.get("/api/vehicles/{id}", ctx -> {
            long vehicleId = Long.parseLong(ctx.pathParam("id"));
            Position pos = null;
            for (IndexerThread indexer : indexers) {
                SpatialSnapshot snapshot = indexer.getPublishedSnapshot();
                if (snapshot != null) {
                    pos = snapshot.hamt().get(vehicleId);
                    if (pos != null) break;
                }
            }
            if (pos == null) {
                ctx.status(404).result("Vehicle not found");
                return;
            }

            RingBuffer<Position> history = positionHistory.computeIfAbsent(vehicleId, k -> new RingBuffer<>(5));

            double speedKmh = 0.0;
            double heading = 0.0;
            String status = "unknown";
            if (history.size() >= 2) {
                Position newest = history.get(history.size() - 1);
                Position oldest = history.get(0);
                double dtSec = (newest.timestamp() - oldest.timestamp()) / 1000.0;
                if (dtSec > 0) {
                    double distM = GeoUtils.haversineMeters(oldest.y(), oldest.x(), newest.y(), newest.x());
                    speedKmh = (distM / dtSec) * 3.6;
                    double lat1 = Math.toRadians(oldest.y());
                    double lat2 = Math.toRadians(newest.y());
                    double dLon = Math.toRadians(newest.x() - oldest.x());
                    double y = Math.sin(dLon) * Math.cos(lat2);
                    double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
                    heading = Math.toDegrees(Math.atan2(y, x));
                    if (heading < 0) heading += 360;
                    status = speedKmh > 0.5 ? "moving" : "idle";
                }
            }

            List<String> zoneList = new ArrayList<>();
            for (Zone zone : zones) {
                if (RayCaster.contains(pos, zone.polygon())) zoneList.add(zone.id());
            }
            for (UserZone zone : geofenceEngine.getAllUserZones()) {
                if (RayCaster.contains(pos, zone.polygon())) zoneList.add(zone.zoneId());
            }

            VehicleDetail detail = new VehicleDetail(vehicleId, pos, speedKmh, heading, zoneList, pos.timestamp(), status);
            ctx.json(detail);
        });

        app.get("/", ctx -> {
            ctx.redirect("/index.html");
        });

        app.get("/index.html", ctx -> {
            ctx.contentType("text/html");
            try (var is = getClass().getClassLoader().getResourceAsStream("public/index.html")) {
                ctx.result(new String(is.readAllBytes()));
            } catch (Exception e) {
                ctx.status(500).result("Failed to load index.html");
            }
        });

        app.get("/app.js", ctx -> {
            ctx.contentType("application/javascript");
            try (var is = getClass().getClassLoader().getResourceAsStream("public/app.js")) {
                ctx.result(new String(is.readAllBytes()));
            } catch (Exception e) {
                ctx.status(500).result("Failed to load app.js");
            }
        });
    }

    private Map<String, Object> zoneToMap(String id, List<Position> polygon, BoundingBox bbox) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("bbox", Map.of(
                "minX", bbox.minX(),
                "minY", bbox.minY(),
                "maxX", bbox.maxX(),
                "maxY", bbox.maxY()
        ));
        List<Map<String, Double>> poly = polygon.stream().map(p -> {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("x", p.x());
            m.put("y", p.y());
            return m;
        }).collect(Collectors.toList());
        map.put("polygon", poly);
        return map;
    }

    static Map<String, Object> positionToMap(long vehicleId, Position pos) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("vehicleId", vehicleId);
        map.put("x", pos.x());
        map.put("y", pos.y());
        map.put("timestamp", pos.timestamp());
        return map;
    }

    private void startPositionBroadcast() {
        BoundingBox fullBounds = new BoundingBox(
                Math.min(Config.MAP_MIN_X, Config.AREA_MIN_LNG),
                Math.min(Config.MAP_MIN_Y, Config.AREA_MIN_LAT),
                Math.max(Config.MAP_MAX_X, Config.AREA_MAX_LNG),
                Math.max(Config.MAP_MAX_Y, Config.AREA_MAX_LAT)
        );
        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<Map<String, Object>> allPositions = new ArrayList<>();
                for (IndexerThread indexer : indexers) {
                    SpatialSnapshot snapshot = indexer.getPublishedSnapshot();
                    if (snapshot == null) continue;
                    CowQuadtree qt = snapshot.quadtree();
                    HamtIndex hamt = snapshot.hamt();
                    List<Long> ids = qt.rangeQuery(fullBounds);
                    for (Long id : ids) {
                        Position pos = hamt.get(id);
                        if (pos == null) continue;
                        positionHistory.computeIfAbsent(id, k -> new RingBuffer<>(5)).put(pos);
                        Map<String, Object> map = positionToMap(id, pos);
                        allPositions.add(map);
                    }
                }
                String json = mapper.writeValueAsString(allPositions);
                for (WsContext client : wsClients) {
                    if (client.session.isOpen()) {
                        client.send(json);
                    }
                }
            } catch (Exception e) {
                System.err.println("WebSocket broadcast error: " + e.getMessage());
            }
        }, 200, 200, TimeUnit.MILLISECONDS);
    }

    public void pushZoneEvent(ZoneEvent event) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "zoneEvent");
            map.put("vehicleId", event.vehicleId());
            map.put("zoneId", event.zoneId());
            map.put("eventType", event.type().name().toLowerCase());
            map.put("timestamp", event.timestamp());
            String json = mapper.writeValueAsString(map);
            for (WsContext client : eventClients) {
                if (client.session.isOpen()) {
                    client.send(json);
                }
            }
        } catch (Exception e) {
            System.err.println("Zone event broadcast error: " + e.getMessage());
        }
    }

    public void stop() {
        scheduler.shutdownNow();
        app.stop();
    }
}
