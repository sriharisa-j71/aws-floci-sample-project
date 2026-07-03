#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "=== Downloading dependencies ==="
go mod download

echo "=== Building binary ==="
go build -o generate-currency-file .

echo "=== Compressing with UPX ==="
upx -o generate-currency-file.upx generate-currency-file
mv generate-currency-file.upx generate-currency-file

echo "=== Done ==="
echo "  binary: generate-currency-file (UPX-compressed)"
ls -lh generate-currency-file
