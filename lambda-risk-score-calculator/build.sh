#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "=== Downloading dependencies ==="
go mod download

echo "=== Building Lambda binary ==="
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o bootstrap .

echo "=== Compressing with UPX ==="
upx -o bootstrap.upx bootstrap || true
mv -f bootstrap.upx bootstrap 2>/dev/null || true

echo "=== Creating deployment zip ==="
zip -j risk-score-calculator.zip bootstrap

echo "=== Copying zip to root for docker volume ==="
cp risk-score-calculator.zip /tmp/lambda-risk-score-calculator.zip 2>/dev/null || true

echo "=== Done ==="
echo "  binary: bootstrap"
echo "  zip:    risk-score-calculator.zip"

ls -lh bootstrap risk-score-calculator.zip
