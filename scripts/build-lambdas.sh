#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== Building Lambda: investor-file-handler ==="
cd "$PROJECT_DIR/lambda-investor-file-handler"
mvn -q package -DskipTests
echo "  JAR: target/investor-file-handler-1.0.jar"

echo "=== Building Lambda: investor-sal-processor ==="
cd "$PROJECT_DIR/lambda-investor-sal-processor"
mvn -q package -DskipTests
echo "  JAR: target/investor-sal-processor-1.0.jar"

echo "=== Lambda builds complete ==="
