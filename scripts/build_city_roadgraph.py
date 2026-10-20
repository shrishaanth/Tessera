"""
Builds the city-scale road graph used by the desktop simulation:
src/main/resources/roadgraph/seattle-central-roadgraph.json.gz

Data: OpenStreetMap (c) OpenStreetMap contributors, licensed under the Open Database License (ODbL) 1.0.
https://www.openstreetmap.org/copyright

Usage:
    python3 scripts/build_city_roadgraph.py                      # fetch from the Overpass API
    python3 scripts/build_city_roadgraph.py --input overpass.json # use a saved Overpass response

What is kept:
  * Ways tagged highway = motorway, trunk, primary, secondary, tertiary (and their _link roads),
    unclassified, residential, living_street. Footways, paths, cycleways, steps, pedestrian streets,
    tracks, service roads (driveways, parking aisles, alleys), construction and proposed roads are
    excluded, so no vehicle drives through parks or along trails.
  * Ways closed to motor vehicles (access/vehicle/motor_vehicle/motorcar = no or private) and
    time-reversible lanes (oneway=reversible/alternating, e.g. the I-5 express lanes) are excluded.
  * One-way rules: oneway=yes/true/1, oneway=-1/reverse, and the implied one-way of motorways,
    motorway links and roundabouts.
  * Only nodes inside the bounding box. Ways that leave the box are cut at its edge.
  * Only the largest strongly connected component, so every node can reach every other node along
    legal driving directions (no dead-end traps behind one-way streets, no unreachable islands).

Coordinates: x = longitude, y = latitude. Edge weight = haversine length in metres (R = 6,371 km).
Edge speed = posted limit in m/s (maxspeed tag, else a per-road-class default for Seattle).
"""

import argparse
import datetime
import gzip
import json
import math
import os
import sys
import urllib.parse
import urllib.request
from collections import defaultdict

# Central Seattle: Ballard / Interbay (west) to Montlake / University District (east),
# Pioneer Square / International District (south) to Green Lake / Roosevelt (north).
SOUTH, WEST, NORTH, EAST = 47.5950, -122.4050, 47.6900, -122.2850
AREA_NAME = "Central Seattle, WA"

DRIVABLE = {
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link",
    "unclassified", "residential", "living_street",
}
# Seattle defaults (city arterials 25 mph, residential 20 mph since 2016/2019).
DEFAULT_KMH = {
    "motorway": 88, "motorway_link": 56, "trunk": 64, "trunk_link": 48,
    "primary": 48, "primary_link": 40, "secondary": 40, "secondary_link": 40,
    "tertiary": 40, "tertiary_link": 40, "unclassified": 32, "residential": 32,
    "living_street": 16,
}
NO_ACCESS = {"no", "private"}

OVERPASS_QUERY = f"""
[out:json][timeout:180];
way["highway"~"^({'|'.join(sorted(DRIVABLE))})$"]({SOUTH},{WEST},{NORTH},{EAST});
out body;
>;
out skel qt;
"""

R = 6371000.0


def haversine(lat1, lon1, lat2, lon2):
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2
    return 2 * R * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def fetch():
    body = urllib.parse.urlencode({"data": OVERPASS_QUERY}).encode()
    req = urllib.request.Request("https://overpass-api.de/api/interpreter", data=body,
                                 headers={"User-Agent": "Tessera-roadgraph-build/1.0"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.load(resp)


def is_drivable(tags):
    if tags.get("highway") not in DRIVABLE or tags.get("area") == "yes":
        return False
    if tags.get("oneway") in ("reversible", "alternating"):
        return False
    for key in ("motor_vehicle", "motorcar", "vehicle"):
        if tags.get(key) in NO_ACCESS:
            return False
        if tags.get(key) in ("yes", "designated", "permissive", "destination"):
            return True
    return tags.get("access") not in NO_ACCESS


def direction(tags):
    """+1 forward only, -1 backward only, 0 both ways."""
    ow = tags.get("oneway")
    if ow in ("yes", "true", "1"):
        return 1
    if ow in ("-1", "reverse"):
        return -1
    if ow in ("no", "false", "0"):
        return 0
    if tags.get("junction") in ("roundabout", "circular") or tags.get("highway") in ("motorway", "motorway_link"):
        return 1
    return 0


def speed_mps(tags):
    raw = (tags.get("maxspeed") or "").strip().lower()
    try:
        if raw.endswith("mph"):
            return float(raw[:-3]) * 0.44704
        if raw:
            return float(raw.split()[0]) / 3.6
    except ValueError:
        pass
    return DEFAULT_KMH[tags["highway"]] / 3.6


def largest_scc(nodes, out_adj):
    """Iterative Kosaraju; returns the node ids of the largest strongly connected component."""
    in_adj = defaultdict(list)
    for u, vs in out_adj.items():
        for v in vs:
            in_adj[v].append(u)
    order, visited = [], set()
    for s in nodes:
        if s in visited:
            continue
        visited.add(s)
        stack = [(s, iter(out_adj.get(s, ())))]
        while stack:
            u, it = stack[-1]
            advanced = False
            for v in it:
                if v not in visited:
                    visited.add(v)
                    stack.append((v, iter(out_adj.get(v, ()))))
                    advanced = True
                    break
            if not advanced:
                stack.pop()
                order.append(u)
    comp, best = {}, set()
    for s in reversed(order):
        if s in comp:
            continue
        members, stack = [s], [s]
        comp[s] = s
        while stack:
            u = stack.pop()
            for v in in_adj.get(u, ()):
                if v not in comp:
                    comp[v] = s
                    members.append(v)
                    stack.append(v)
        if len(members) > len(best):
            best = set(members)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", help="saved Overpass JSON response")
    ap.add_argument("--output", default=os.path.join(os.path.dirname(__file__), "..", "src", "main",
                                                      "resources", "roadgraph", "seattle-central-roadgraph.json.gz"))
    args = ap.parse_args()

    if args.input:
        with open(args.input) as f:
            data = json.load(f)
        source_date = datetime.datetime.fromtimestamp(os.path.getmtime(args.input)).date().isoformat()
    else:
        data = fetch()
        source_date = datetime.date.today().isoformat()
    osm_base = data.get("osm3s", {}).get("timestamp_osm_base", "")

    coords = {}
    ways = []
    for el in data["elements"]:
        if el["type"] == "node":
            coords[el["id"]] = (el["lon"], el["lat"])
        elif el["type"] == "way":
            ways.append(el)

    def inside(nid):
        c = coords.get(nid)
        return c is not None and WEST <= c[0] <= EAST and SOUTH <= c[1] <= NORTH

    edges = {}  # (from, to) -> (weight, speed)
    excluded = defaultdict(int)
    for way in ways:
        tags = way.get("tags", {})
        if not is_drivable(tags):
            excluded[tags.get("highway", "?")] += 1
            continue
        d = direction(tags)
        v = speed_mps(tags)
        refs = way.get("nodes", [])
        for a, b in zip(refs, refs[1:]):
            if a == b or not inside(a) or not inside(b):
                continue
            (x1, y1), (x2, y2) = coords[a], coords[b]
            w = haversine(y1, x1, y2, x2)
            if w <= 0.01:
                continue
            pairs = [(a, b)] if d == 1 else [(b, a)] if d == -1 else [(a, b), (b, a)]
            for p in pairs:
                if p not in edges or w < edges[p][0]:
                    edges[p] = (w, v)

    out_adj = defaultdict(list)
    used = set()
    for (a, b) in edges:
        out_adj[a].append(b)
        used.add(a)
        used.add(b)
    keep = largest_scc(used, out_adj)
    kept_edges = {k: v for k, v in edges.items() if k[0] in keep and k[1] in keep}

    xs = [coords[n][0] for n in keep]
    ys = [coords[n][1] for n in keep]
    one_way = sum(1 for (a, b) in kept_edges if (b, a) not in kept_edges)
    length_km = sum(w for (w, _) in kept_edges.values()) / 1000.0
    undirected_km = sum(w for (a, b), (w, _) in kept_edges.items() if (b, a) not in kept_edges or a < b) / 1000.0

    out = {
        "area": {"south": SOUTH, "west": WEST, "north": NORTH, "east": EAST, "name": AREA_NAME},
        "source": {
            "attribution": "© OpenStreetMap contributors",
            "license": "ODbL 1.0 (https://www.openstreetmap.org/copyright)",
            "via": "Overpass API",
            "osm_base": osm_base,
            "extracted": source_date,
            "filter": "highway in {" + ", ".join(sorted(DRIVABLE)) + "}; excludes no/private access and "
                      "reversible lanes; one-way rules applied; largest strongly connected component",
        },
        "stats": {
            "nodes": len(keep), "directed_edges": len(kept_edges), "one_way_edges": one_way,
            "road_km_directed": round(length_km, 1), "road_km_undirected": round(undirected_km, 1),
            "nodes_before_scc": len(used),
            "extent": {"min_lng": min(xs), "max_lng": max(xs), "min_lat": min(ys), "max_lat": max(ys)},
        },
        "nodes": [{"id": n, "x": round(coords[n][0], 7), "y": round(coords[n][1], 7)} for n in sorted(keep)],
        "edges": [{"from": a, "to": b, "weight": round(w, 2), "speed": round(v, 2)}
                  for (a, b), (w, v) in sorted(kept_edges.items())],
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with gzip.open(args.output, "wt", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"), ensure_ascii=False)

    print(f"ways kept/total: {sum(1 for w in ways if is_drivable(w.get('tags', {})))}/{len(ways)}", file=sys.stderr)
    print(f"excluded ways by highway type: {dict(excluded)}", file=sys.stderr)
    print(f"nodes in largest SCC: {len(keep)} of {len(used)} ({100.0 * len(keep) / max(1, len(used)):.1f}%)", file=sys.stderr)
    print(f"directed edges: {len(kept_edges)} (one-way: {one_way}), road length {undirected_km:.0f} km", file=sys.stderr)
    print(f"wrote {args.output} ({os.path.getsize(args.output) / 1e6:.1f} MB)", file=sys.stderr)


if __name__ == "__main__":
    main()
