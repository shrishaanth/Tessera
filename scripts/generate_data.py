"""
Generate data files for Tessera trajectory demo.
"""
import json
import math
import csv
import os

# Grid parameters
COLS = 10
ROWS = 7
MIN_LNG = -122.334
MAX_LNG = -122.330
MIN_LAT = 47.646
MAX_LAT = 47.650

LNG_STEP = (MAX_LNG - MIN_LNG) / (COLS - 1)
LAT_STEP = (MAX_LAT - MIN_LAT) / (ROWS - 1)

EARTH_RADIUS = 6371000.0

def haversine(lat1, lon1, lat2, lon2):
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat/2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon/2)**2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return EARTH_RADIUS * c

# Generate nodes
nodes = []
for r in range(ROWS):
    for c in range(COLS):
        lng = MIN_LNG + c * LNG_STEP
        lat = MIN_LAT + r * LAT_STEP
        nodes.append({"id": r * COLS + c, "x": lng, "y": lat})

# Generate edges (bidirectional, horizontal + vertical)
edges = []
for r in range(ROWS):
    for c in range(COLS):
        nid = r * COLS + c
        # right
        if c < COLS - 1:
            nid2 = r * COLS + (c + 1)
            w = haversine(nodes[nid]["y"], nodes[nid]["x"], nodes[nid2]["y"], nodes[nid2]["x"])
            edges.append({"from": nid, "to": nid2, "weight": w})
            edges.append({"from": nid2, "to": nid, "weight": w})
        # down
        if r < ROWS - 1:
            nid2 = (r + 1) * COLS + c
            w = haversine(nodes[nid]["y"], nodes[nid]["x"], nodes[nid2]["y"], nodes[nid2]["x"])
            edges.append({"from": nid, "to": nid2, "weight": w})
            edges.append({"from": nid2, "to": nid, "weight": w})

graph = {"nodes": nodes, "edges": edges}
out_dir = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "roadgraph")
os.makedirs(out_dir, exist_ok=True)
with open(os.path.join(out_dir, "grid_roadgraph.json"), "w") as f:
    json.dump(graph, f, indent=2)
print(f"Wrote grid_roadgraph.json: {len(nodes)} nodes, {len(edges)} edges")

# Generate simple trips for each vehicle (a few random paths through the grid)
import random
random.seed(42)

def random_trip():
    path = []
    r = random.randint(0, ROWS - 1)
    c = random.randint(0, COLS - 1)
    for _ in range(random.randint(3, 8)):
        path.append(r * COLS + c)
        if random.random() < 0.5 and c < COLS - 1:
            c += 1
        elif random.random() < 0.5 and r < ROWS - 1:
            r += 1
        elif c > 0:
            c -= 1
        elif r > 0:
            r -= 1
    path.append(r * COLS + c)
    return path

trips = {str(i): random_trip() for i in range(20)}
with open(os.path.join(out_dir, "..", "data", "vehicle_trips.json"), "w") as f:
    json.dump(trips, f, indent=2)
print(f"Wrote vehicle_trips.json: {len(trips)} trips")

# Generate vehicle_trajectories.csv
# For each trip, interpolate positions along edges at ~200ms intervals
out_dir = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data")
os.makedirs(out_dir, exist_ok=True)

def lerp(a, b, t):
    return a + (b - a) * t

def interp_trip(trip):
    points = []
    t = 0
    for i in range(len(trip) - 1):
        n1 = nodes[trip[i]]
        n2 = nodes[trip[i+1]]
        dist = haversine(n1["y"], n1["x"], n2["y"], n2["x"])
        speed = random.uniform(4.0, 15.0)  # m/s
        steps = max(1, int(dist / (speed * 0.2)))  # 200ms per step
        for s in range(steps):
            frac = s / steps
            lat = lerp(n1["y"], n2["y"], frac)
            lng = lerp(n1["x"], n2["x"], frac)
            speed_kmh = speed * 3.6
            points.append((t, lat, lng, speed_kmh))
            t += 200
    # final point
    n = nodes[trip[-1]]
    points.append((t, n["y"], n["x"], 0.0))
    return points

with open(os.path.join(out_dir, "vehicle_trajectories.csv"), "w", newline="") as f:
    writer = csv.writer(f)
    writer.writerow(["vehicle_id", "timestamp_ms", "latitude", "longitude", "speed_kmh"])
    for vid in range(20):
        trip = trips[str(vid)]
        pts = interp_trip(trip)
        for t, lat, lng, speed in pts:
            writer.writerow([vid, t, f"{lat:.6f}", f"{lng:.6f}", f"{speed:.1f}"])
print(f"Wrote vehicle_trajectories.csv")
