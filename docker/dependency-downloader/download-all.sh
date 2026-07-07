#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Step 1: Spark ==="
bash "${DIR}/download-spark.sh"

echo ""
echo "=== Step 2: JAR dependencies ==="
bash "${DIR}/download-deps.sh"

echo ""
echo "=== All downloads complete ==="
echo "Run 'docker build -t glue-scala-minimal:latest ../' from the docker/ directory to build the image."
