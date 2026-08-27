package com.geotracker.web;

import com.geotracker.geofence.GeofenceEngine;
import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.index.IndexerThread;
import com.geotracker.index.SpatialSnapshot;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.RouteResult;
import com.geotracker.routing.AStarRouter;
import com.geotracker.routing.RoadGraph;
import com.geotracker.util.Config;

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
    private final List<GeofenceEngine.Zone> zones;
    private final List<WsContext> wsClients = new CopyOnWriteArrayList<>();

    public WebServer(List<IndexerThread> indexers, AStarRouter router, RoadGraph graph, List<GeofenceEngine.Zone> zones) {
        this.indexers = indexers;
        this.router = router;
        this.graph = graph;
        this.zones = zones;
        this.mapper = new ObjectMapper();
        this.app = Javalin.create().start(Config.WEB_PORT);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        configureRoutes();
        startPositionBroadcast();
    }

    private void configureRoutes() {
        app.ws("/ws/positions", ws -> {
            ws.onConnect(ctx -> {
                wsClients.add(ctx);
            });
            ws.onClose(ctx -> {
                wsClients.remove(ctx);
            });
            ws.onError(ctx -> {
                wsClients.remove(ctx);
            });
        });

        app.get("/api/geofences", ctx -> {
            List<Map<String, Object>> result = zones.stream().map(zone -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", zone.id());
                map.put("bbox", Map.of(
                        "minX", zone.bbox().minX(),
                        "minY", zone.bbox().minY(),
                        "maxX", zone.bbox().maxX(),
                        "maxY", zone.bbox().maxY()
                ));
                List<Map<String, Double>> polygon = zone.polygon().stream().map(p -> {
                    Map<String, Double> m = new LinkedHashMap<>();
                    m.put("x", p.x());
                    m.put("y", p.y());
                    return m;
                }).collect(Collectors.toList());
                map.put("polygon", polygon);
                return map;
            }).collect(Collectors.toList());
            ctx.json(result);
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

                RouteResult routeResult = router.findRoute(vehicleId, startPos, destX, destY);
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
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("vehicleId", routeResult.vehicleId());
                response.put("totalCost", routeResult.totalCost());
                response.put("nodes", nodes);
                ctx.json(response);
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid parameter format");
            }
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

    private void startPositionBroadcast() {
        BoundingBox fullBounds = new BoundingBox(Config.MAP_MIN_X, Config.MAP_MIN_Y, Config.MAP_MAX_X, Config.MAP_MAX_Y);
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
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("vehicleId", id);
                        map.put("x", pos.x());
                        map.put("y", pos.y());
                        map.put("timestamp", pos.timestamp());
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

    public void stop() {
        scheduler.shutdownNow();
        app.stop();
    }
}
