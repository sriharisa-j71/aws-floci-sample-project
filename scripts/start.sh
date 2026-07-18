#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# shellcheck source=/dev/null
source "$ROOT_DIR/.floci.env"

cd "$ROOT_DIR"

echo "=== Starting services ==="
docker compose up -d

echo ""
echo "=== Running infrastructure setup ==="
bash "$SCRIPT_DIR/setup-infra.sh"

FLOCI_PORT=$(echo "$AWS_ENDPOINT_URL" | grep -oP ':\K[0-9]+')

echo ""
echo "=== All services are ready ==="
echo ""
echo "Services:"
echo "  - WireMock:  http://localhost:8080"
echo "  - Floci:     http://localhost:${FLOCI_PORT}"
echo "  - Postgres:  localhost:5432"
echo ""
echo "To submit a Glue job, run:"
echo "  docker exec glue-runner bash /opt/glue-runner.sh"