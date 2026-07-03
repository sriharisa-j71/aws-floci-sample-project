#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "=== Downloading dependencies ==="
go mod download

echo "=== Building binary ==="
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o generate-data .

echo "=== Compressing with UPX ==="
upx -o generate-data.upx generate-data
mv generate-data.upx generate-data

echo "=== Done ==="
echo "  binary: generate-data (UPX-compressed)"
ls -lh generate-data
