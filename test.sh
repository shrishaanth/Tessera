#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Tessera Test Suite ==="
echo ""

mvn clean test

echo ""
echo "=== All tests passed ==="
