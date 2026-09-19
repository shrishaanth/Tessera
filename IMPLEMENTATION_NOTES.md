# Tessera Implementation Notes

## Problem 1 — Vehicles Jump Around Randomly

### Fix
Replaced the random-walk `VehicleSimulator` with a new `TrajectorySimulator` that replays pre-recorded GPS trajectories from `vehicle_trajectories.csv`.

**New file:** `src/main/java/com/geotracker/simulator/TrajectorySimulator.java`
- Loads CSV once at startup, groups records by `vehicleId`
- Maintains a global simulation clock (`simTime`) advanced by `dt * timeScale` each tick
- For each vehicle, binary-searches its trajectory for the record closest to current sim time
- Returns `PositionUpdate(vehicleId, longitude, latitude, System.currentTimeMillis())`
- Supports `timeScale` for playback speed adjustment
- Loops back to start when trajectory ends (continuous demo)

**Modified files:**
- `WebDemoMain.java` — Added `--trajectory` flag (now the default mode). When active, loads `grid_roadgraph.json` and uses `TrajectorySimulator` with 20 vehicles. `--grid` and `--osm` flags still work for legacy modes.
- `Config.java` — Added `TRAJECTORY_RESOURCE` and `GRID_ROADGRAPH_RESOURCE` constants.

**Data files generated:**
- `src/main/resources/data/vehicle_trajectories.csv` — 20 vehicles, ~53K records at 200ms intervals
- `src/main/resources/roadgraph/grid_roadgraph.json` — 70-node grid (10×7) with bidirectional edges, Haversine weights
- `src/main/resources/data/vehicle_trips.json` — 20 precomputed trip paths

**Verification:**
- WebSocket broadcast shows 20 vehicles with smooth, continuous movement
- No teleportation observed; positions change gradually along recorded paths
- Route requests return valid paths on the grid graph

---

## Problem 2 — Dynamic Geofencing

### Fix
Extended `GeofenceEngine` with full CRUD support for user-defined zones.

**Modified file:** `src/main/java/com/geotracker/geofence/GeofenceEngine.java`
- Added `UserZone` record with: `zoneId`, `name`, `polygon`, `bbox`, `monitoredVehicleIds` (empty = all vehicles), `alertOnEnter`, `alertOnExit`, `createdAt`
- Added `ConcurrentHashMap<String, UserZone> userZones` for thread-safe zone storage
- Added methods: `createZone()`, `deleteZone()`, `updateZone()`, `getAllUserZones()`, `getUserZone()`
- Modified `check()` to iterate both hardcoded zones and user zones, applying per-zone vehicle filtering

**New REST endpoints in `WebServer.java`:**
- `GET /api/zones` — Returns all zones (hardcoded + user-defined)
- `POST /api/zones` — Creates a new zone
- `DELETE /api/zones/{id}` — Deletes a zone
- `PUT /api/zones/{id}` — Updates a zone
- `PATCH /api/zones/{id}/vehicles` — Updates monitored vehicle IDs

**New model classes:**
- `src/main/java/com/geotracker/model/ZoneRequest.java`
- `src/main/java/com/geotracker/model/SearchRequest.java`
- `src/main/java/com/geotracker/model/VehicleDetail.java`

---

## Problem 3 — Spatial Rectangle Query

### Fix
Added `POST /api/vehicles/search` endpoint in `WebServer.java`.

- Accepts `{bbox: {minX, minY, maxX, maxY}, vehicleIds: [optional]}`
- Queries each shard's `CowQuadtree` with the bbox
- Filters by `vehicleIds` if provided
- Returns matching vehicle positions

**Frontend:** "Find Vehicles in Area" tool in the Search panel. Click the button to enter draw mode, then click the map to place a search rectangle. Results show vehicle count and list with [Track] buttons.

---

## Problem 4 — Route Planner UX

### Fix
Enhanced `/api/route` response with:
- `distanceMeters` (same as `totalCost`)
- `estimatedSeconds` (distance / 5.0 m/s assumed speed)

Added `GET /api/vehicles/{id}` endpoint returning current position.

**Frontend:** New "Route Planner" sidebar panel with:
- Vehicle ID input + [Find Vehicle] button
- [Set Destination] mode (crosshair cursor)
- Route info display (distance + ETA)
- [Clear Route] button
- Map clicks ONLY trigger routing when destination mode is active

---

## Problem 5 — Rich Vehicle Detail

### Fix
`GET /api/vehicles/{id}` now returns `VehicleDetail` with:
- `speedKmh` — computed from last 5 positions using Haversine distance / time delta
- `heading` — degrees from North (0-360), computed via `atan2`
- `zones` — list of zone IDs the vehicle is currently inside
- `status` — "moving" if speed > 0.5 km/h, "idle" otherwise

**Implementation:** WebServer maintains `ConcurrentHashMap<Long, RingBuffer<Position>>` with a 5-position sliding window per vehicle. Speed/heading are computed on each `/api/vehicles/{id}` request.

**Frontend:** Clicking a vehicle marker opens a detail panel (top-right overlay) showing position, speed, heading, zones, and status. [Track] and [Plan Route] buttons link to the Tracker and Route Planner panels.

---

## Problem 6 — Vehicle Tracking

### Fix
Added "Vehicle Tracker" sidebar panel with:
- Search by ID → pans map, selects vehicle, shows detail panel
- [Track Vehicle] adds a fading orange trail (last 50 positions as polyline)
- [Stop All Tracking] clears all trails
- Active tracks listed with per-vehicle [Stop] buttons

**Trail implementation in `app.js`:**
```javascript
const trails = new Map(); // vehicleId -> {polyline, positions[]}

// On each WebSocket update:
if (trails.has(pos.vehicleId)) {
    const trail = trails.get(pos.vehicleId);
    trail.positions.push([pos.y, pos.x]);
    if (trail.positions.length > 50) trail.positions.shift();
    trail.polyline.setLatLngs(trail.positions);
}
```

---

## Problem 7 — UI/UX Improvements

### Fix
Complete frontend rewrite with:

**Layout (`index.html`):**
- Full-screen Leaflet map (`L.CRS.EPSG3857` with OSM tiles)
- Left sidebar (320px, collapsible) with 4 tabs: [Tracker] [Geofences] [Routes] [Search]
- Top-right status bar: Live/Disconnected dot, vehicle count, zone count, alert count
- Bottom-left alert log (scrollable, last 50 events)
- Vehicle detail panel (top-right, appears on marker click)
- Modal dialog for zone creation

**Styling:**
- Dark theme sidebar (`#1a1a2e` background)
- Status dots: green=connected, red=disconnected, yellow=reconnecting
- Vehicle markers: blue=default, green=selected, red=in geofence, yellow=tracked
- Route line: `#e91e63`, 4px solid
- Zone polygons: colored borders, 15% fill opacity

**WebSocket reconnection:**
- Both `/ws/positions` and `/ws/events` auto-reconnect with 2-second backoff
- Status dot updates accordingly

**Drawing tools:**
- Leaflet.Draw CDN for rectangle and polygon drawing
- Rectangle draw for geofences and area search
- Polygon draw for custom geofence shapes

---

## Additional Changes

### RingBuffer Made Generic
`src/main/java/com/geotracker/util/RingBuffer.java` was converted from `RingBuffer` (raw `PositionUpdate[]`) to `RingBuffer<T>` to support storing both `PositionUpdate` (ingestion pipeline) and `Position` (vehicle history for speed/heading computation).

### GeoUtils Utility
`src/main/java/com/geotracker/routing/GeoUtils.java` — extracted Haversine formula into a reusable public utility. `AStarRouter` and `WebServer` both delegate to `GeoUtils.haversineMeters(...)`.

### Tests Added
- `RoadGraphJsonLoaderJUnitTest` — JSON graph loading + malformed input rejection
- `GeoUtilsJUnitTest` — Haversine known distances
- `WebServerJsonSerializationJUnitTest` — position JSON shape

All 64 tests pass (`mvn clean test` → BUILD SUCCESS).

---

## How to Run

```bash
# Default: trajectory mode with 20 vehicles on grid road graph
java -cp "target\classes;lib\*" com.geotracker.WebDemoMain

# Legacy OSM mode
java -cp "target\classes;lib\*" com.geotracker.WebDemoMain --osm

# Legacy synthetic grid mode
java -cp "target\classes;lib\*" com.geotracker.WebDemoMain --grid
```

Then open `http://localhost:8081`.
