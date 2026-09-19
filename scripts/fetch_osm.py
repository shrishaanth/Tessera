"""
One-off OSM data prep script: fetches highway ways from Overpass API
for a small bounding box, builds a RoadGraph-compatible JSON,
and writes it to src/main/resources/roadgraph/osm-roadgraph.json.

Coordinate convention (documented in code):
  x = longitude (matches GeoJSON/Leaflet [lng, lat] convention)
  y = latitude

Edge weights are Haversine distances in meters.
"""

import json
import math
import os
import requests

# ---------------------------------------------------------------------------
# Pick one small, real area.  This is around Gas Works Park, Seattle, WA.
# Approx size: ~500 m x 500 m.
# ---------------------------------------------------------------------------
SOUTH = 47.6460
WEST  = -122.3340
NORTH = 47.6500
EAST  = -122.3300

OVERPASS_URL = "https://overpass-api.de/api/interpreter"

QUERY = f"""
[out:json];
way["highway"]({SOUTH},{WEST},{NORTH},{EAST});
out body;
>;
out skel qt;
"""

print(f"Querying Overpass API for bbox: [{SOUTH},{WEST},{NORTH},{EAST}] ...")
resp = requests.post(
    OVERPASS_URL,
    data=QUERY,
    headers={"User-Agent": "Tessera-OSM-Prep/1.0", "Accept": "application/json"},
    timeout=60
)
resp.raise_for_status()
data = resp.json()
print(f"Received {len(data['elements'])} elements")

nodes = {}
ways = []

for el in data["elements"]:
    if el["type"] == "node":
        nodes[el["id"]] = {"id": el["id"], "lat": el["lat"], "lon": el["lon"]}
    elif el["type"] == "way":
        ways.append(el)

print(f"Found {len(nodes)} nodes, {len(ways)} ways")

EARTH_RADIUS = 6371000.0

def haversine(lat1, lon1, lat2, lon2):
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat/2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon/2)**2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return EARTH_RADIUS * c

graph_nodes = []
graph_edges = []
seen_node_ids = set()

for way in ways:
    refs = way.get("nodes", [])
    if len(refs) < 2:
        continue

    oneway = way.get("tags", {}).get("oneway", "no") == "yes"

    for i in range(len(refs) - 1):
        n1 = nodes.get(refs[i])
        n2 = nodes.get(refs[i+1])
        if not n1 or not n2:
            continue

        w = haversine(n1["lat"], n1["lon"], n2["lat"], n2["lon"])
        if w <= 0:
            continue

        for n in (n1, n2):
            if n["id"] not in seen_node_ids:
                seen_node_ids.add(n["id"])
                graph_nodes.append({
                    "id": n["id"],
                    "x": n["lon"],
                    "y": n["lat"]
                })

        graph_edges.append({"from": refs[i], "to": refs[i+1], "weight": w})
        if not oneway:
            graph_edges.append({"from": refs[i+1], "to": refs[i], "weight": w})

output = {
    "area": {
        "south": SOUTH,
        "west": WEST,
        "north": NORTH,
        "east": EAST,
        "name": "Gas Works Park, Seattle, WA"
    },
    "nodes": graph_nodes,
    "edges": graph_edges
}

out_dir = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "roadgraph")
os.makedirs(out_dir, exist_ok=True)
out_path = os.path.join(out_dir, "osm-roadgraph.json")

with open(out_path, "w") as f:
    json.dump(output, f, indent=2)

print(f"Wrote {len(graph_nodes)} nodes, {len(graph_edges)} edges to {out_path}")
print(f"Approx graph memory: nodes={len(graph_nodes)}, edges={len(graph_edges)}")
