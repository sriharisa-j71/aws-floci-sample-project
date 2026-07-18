#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "=== Tidying modules ==="
go mod tidy

echo "=== Downloading dependencies ==="
go mod download

echo "=== Building Lambda binary ==="
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o bootstrap .

echo "=== Compressing with UPX ==="
upx -o bootstrap.upx bootstrap || true
mv -f bootstrap.upx bootstrap 2>/dev/null || true

echo "=== Creating deployment zip ==="
zip -j sql-query-runner.zip bootstrap queries.toml

echo "=== Done ==="
ls -lh bootstrap sql-query-runner.zip
