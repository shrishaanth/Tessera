#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Tessera - Real-Time Geospatial Vehicle Tracking ==="
echo ""

# Check Java
if ! command -v java &> /dev/null; then
    echo "ERROR: Java is not installed or not in PATH."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
if [ "$JAVA_VERSION" -lt 21 ] 2>/dev/null; then
    echo "WARNING: Java 21+ recommended, found version $JAVA_VERSION"
fi

# Check Maven
if ! command -v mvn &> /dev/null; then
    echo "ERROR: Maven is not installed or not in PATH."
    exit 1
fi

# Build if needed
if [ ! -d "target/classes" ] || [ "$(find src -newer target/classes -name '*.java' 2>/dev/null | head -1)" != "" ]; then
    echo "Building Tessera..."
    mvn compile -q
fi

echo "Launching Tessera JavaFX application..."
echo ""

mvn javafx:run -q 2>&1
