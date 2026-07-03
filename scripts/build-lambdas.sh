#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== Building Lambda: employee-file-handler ==="
cd "$PROJECT_DIR/lambda-employee-file-handler"
mvn -q package -DskipTests
echo "  JAR: target/employee-file-handler-1.0.jar"

echo "=== Building Lambda: employee-sal-processor ==="
cd "$PROJECT_DIR/lambda-employee-sal-processor"
mvn -q package -DskipTests
echo "  JAR: target/employee-sal-processor-1.0.jar"

echo "=== Lambda builds complete ==="
