#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Tessera Validation Suite ==="
echo ""

echo "1. Building..."
mvn clean compile -q
echo "   BUILD: PASS"

echo ""
echo "2. Running tests..."
mvn test -q
echo "   TESTS: PASS"

echo ""
echo "3. Verifying datasets..."
TRAJ_FILE="src/main/resources/data/vehicle_trajectories.csv"
if [ -f "$TRAJ_FILE" ]; then
    LINES=$(wc -l < "$TRAJ_FILE" | tr -d ' ')
    echo "   Trajectory CSV: $LINES lines"
    VEHICLES=$(awk -F, 'NR>1{print $1}' "$TRAJ_FILE" | sort -un | wc -l | tr -d ' ')
    echo "   Unique vehicles: $VEHICLES"
    echo "   DATASET: PASS"
else
    echo "   DATASET: MISSING"
fi

ROAD_FILE="src/main/resources/roadgraph/osm-roadgraph.json"
if [ -f "$ROAD_FILE" ]; then
    echo "   OSM road graph: present ($(wc -c < "$ROAD_FILE" | tr -d ' ') bytes)"
    echo "   ROADGRAPH: PASS"
else
    echo "   ROADGRAPH: MISSING"
fi

echo ""
echo "4. Checking JavaFX dependencies..."
mvn dependency:resolve -q 2>/dev/null && echo "   DEPENDENCIES: PASS" || echo "   DEPENDENCIES: FAIL"

echo ""
echo "=== Validation Complete ==="
