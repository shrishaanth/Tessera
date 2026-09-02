#!/usr/bin/env python3
"""
build_roadgraph.py — Fetch a real road network from OpenStreetMap (Overpass API)
and convert it into a routing graph JSON consumed by the Tessera Fleet backend.

The output is real OSM data (ODbL). It is used for:
  * travel-time-aware nearest-available-vehicle ranking (FR-2.2)
  * the deterministic vehicle simulator, which moves vehicles along real edges

Usage:
    python build_roadgraph.py --bbox 42.3540,-71.0750,42.3680,-71.0550 \
        --out ../../backend/src/main/resources/roadgraph/roadgraph.json \
        --name "Downtown Boston (demo area)"

If --from-file is given, an existing Overpass JSON response is used instead of
making a network call (useful for offline/reproducible builds).
"""
import argparse
import json
import math
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone

OVERPASS_URL = "https://overpass-api.de/api/interpreter"

# Drivable highway classes and their default speeds (km/h) when maxspeed is absent.
HIGHWAY_DEFAULT_KPH = {
    "motorway": 100, "motorway_link": 60,
    "trunk": 80, "trunk_link": 50,
    "primary": 50, "primary_link": 40,
    "secondary": 45, "secondary_link": 40,
    "tertiary": 40, "tertiary_link": 35,
    "unclassified": 35,
    "residential": 30,
    "living_street": 15,
    "service": 20,
}

EARTH_RADIUS_M = 6_371_000.0


def haversine_m(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def parse_maxspeed(raw):
    if not raw:
        return None
    raw = raw.strip().lower()
    try:
        if "mph" in raw:
            return float(raw.replace("mph", "").strip()) * 1.60934
        return float(raw.split(";")[0].strip())
    except ValueError:
        return None


def fetch_overpass(bbox, retries=3):
    s, w, n, e = bbox
    query = (
        "[out:json][timeout:60];"
        f'way["highway"]["highway"!~"^(footway|path|cycleway|pedestrian|steps|'
        f'bridleway|track|corridor|proposed|construction|raceway)$"]'
        f"({s},{w},{n},{e});"
        "(._;>;);out body;"
    )
    data = urllib.parse.urlencode({"data": query}).encode()
    last_err = None
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(
                OVERPASS_URL, data=data,
                headers={"User-Agent": "tessera-fleet-roadgraph-builder/1.0"},
            )
            with urllib.request.urlopen(req, timeout=90) as resp:
                return json.loads(resp.read().decode())
        except Exception as ex:  # noqa: BLE001
            last_err = ex
            print(f"  Overpass attempt {attempt}/{retries} failed: {ex}", file=sys.stderr)
            time.sleep(5 * attempt)
    raise RuntimeError(f"Overpass fetch failed: {last_err}")


def build_graph(overpass, bbox, name):
    nodes_raw = {}
    ways = []
    for el in overpass.get("elements", []):
        if el["type"] == "node":
            nodes_raw[el["id"]] = (el["lat"], el["lon"])
        elif el["type"] == "way" and "nodes" in el:
            ways.append(el)

    used = set()
    edges = []
    for way in ways:
        tags = way.get("tags", {})
        hw = tags.get("highway")
        speed = parse_maxspeed(tags.get("maxspeed")) or HIGHWAY_DEFAULT_KPH.get(hw, 30)
        oneway = tags.get("oneway", "no")
        forward_only = oneway in ("yes", "true", "1")
        backward_only = oneway in ("-1", "reverse")
        wname = tags.get("name", tags.get("ref", hw or "road"))
        seq = way["nodes"]
        for a, b in zip(seq, seq[1:]):
            if a not in nodes_raw or b not in nodes_raw:
                continue
            la, lo = nodes_raw[a]
            lb, lob = nodes_raw[b]
            length = haversine_m(la, lo, lb, lob)
            if length <= 0:
                continue
            travel = length / (speed / 3.6)
            if not backward_only:
                edges.append((a, b, length, speed, travel, wname))
            if not forward_only:
                edges.append((b, a, length, speed, travel, wname))
            used.add(a)
            used.add(b)

    # Keep only the largest weakly-connected component so routing never dead-ends.
    adj = {}
    for a, b, *_ in edges:
        adj.setdefault(a, set()).add(b)
        adj.setdefault(b, set()).add(a)
    seen = set()
    best = set()
    for start in used:
        if start in seen:
            continue
        stack, comp = [start], set()
        while stack:
            cur = stack.pop()
            if cur in comp:
                continue
            comp.add(cur)
            seen.add(cur)
            stack.extend(adj.get(cur, ()))
        if len(comp) > len(best):
            best = comp

    nodes_out = [
        {"id": nid, "lat": round(nodes_raw[nid][0], 7), "lon": round(nodes_raw[nid][1], 7)}
        for nid in sorted(best)
    ]
    edges_out = [
        {
            "from": a, "to": b,
            "lengthM": round(length, 2),
            "speedKph": round(speed, 1),
            "travelSec": round(travel, 3),
            "name": wname,
        }
        for (a, b, length, speed, travel, wname) in edges
        if a in best and b in best
    ]
    s, w, n, e = bbox
    return {
        "name": name,
        "source": "OpenStreetMap contributors, via Overpass API (ODbL)",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "area": {"minLat": s, "minLon": w, "maxLat": n, "maxLon": e},
        "nodeCount": len(nodes_out),
        "edgeCount": len(edges_out),
        "nodes": nodes_out,
        "edges": edges_out,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbox", default="42.3540,-71.0750,42.3680,-71.0550",
                    help="south,west,north,east")
    ap.add_argument("--out", required=True)
    ap.add_argument("--name", default="Demo area")
    ap.add_argument("--from-file", help="use an existing Overpass JSON file instead of fetching")
    args = ap.parse_args()

    bbox = tuple(float(x) for x in args.bbox.split(","))
    if len(bbox) != 4:
        ap.error("--bbox must be south,west,north,east")

    if args.from_file:
        print(f"Reading Overpass response from {args.from_file}")
        with open(args.from_file, encoding="utf-8") as fh:
            overpass = json.load(fh)
    else:
        print(f"Fetching OSM road network for bbox {bbox} ...")
        overpass = fetch_overpass(bbox)

    graph = build_graph(overpass, bbox, args.name)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(graph, fh, separators=(",", ":"))
    print(f"Wrote {args.out}: {graph['nodeCount']} nodes, {graph['edgeCount']} edges")


if __name__ == "__main__":
    main()
