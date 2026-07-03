#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "=== Downloading dependencies ==="
go mod download

echo "=== Building Lambda binary ==="
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o bootstrap .

echo "=== Compressing with UPX ==="
upx -o bootstrap.upx bootstrap
mv bootstrap.upx bootstrap

echo "=== Creating deployment zip ==="
zip -j currency-refresh.zip bootstrap

echo "=== Done ==="
echo "  binary: bootstrap (UPX-compressed)"
echo "  zip:    currency-refresh.zip"

ls -lh bootstrap currency-refresh.zip
