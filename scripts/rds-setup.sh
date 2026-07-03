#!/usr/bin/env bash
set -euo pipefail

FLOCI_HOST="${1:-floci}"
FLOCI_PORT="${2:-4566}"
ENDPOINT_URL="http://${FLOCI_HOST}:${FLOCI_PORT}"

echo "=== Creating RDS instance ==="
aws rds create-db-instance \
  --db-instance-identifier emp-db \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --master-username admin \
  --master-user-password secret123 \
  --allocated-storage 20 \
  --endpoint-url "$ENDPOINT_URL" \
  --region "$AWS_DEFAULT_REGION" 2>/dev/null || true

echo "=== Waiting for RDS endpoint ==="
for i in $(seq 1 30); do
  RDS_HOST=$(aws rds describe-db-instances \
    --db-instance-identifier emp-db \
    --endpoint-url "$ENDPOINT_URL" \
    --region "$AWS_DEFAULT_REGION" \
    --query 'DBInstances[0].Endpoint.Address' --output text 2>/dev/null || true)
  if [ "$RDS_HOST" != "None" ] && [ -n "$RDS_HOST" ]; then
    echo "RDS ready at $RDS_HOST:7001"
    break
  fi
  echo "  waiting... ($i/30)"
  sleep 2
done

echo "=== Waiting for PostgreSQL to accept connections ==="
for i in $(seq 1 15); do
  if PGPASSWORD=secret123 psql -h "$FLOCI_HOST" -p 7001 -U admin -d postgres -c "SELECT 1" &>/dev/null; then
    echo "PostgreSQL ready"
    break
  fi
  echo "  waiting... ($i/15)"
  sleep 1
done

echo "=== Seeding database ==="
PGPASSWORD=secret123 psql -h "$FLOCI_HOST" -p 7001 -U admin -d postgres -f /opt/sql/schema.sql
PGPASSWORD=secret123 psql -h "$FLOCI_HOST" -p 7001 -U admin -d postgres -f /opt/sql/data.sql
echo "=== Creating S3 buckets ==="
aws s3 mb s3://emp-input --endpoint-url "$ENDPOINT_URL" 2>/dev/null || true
aws s3 mb s3://emp-output --endpoint-url "$ENDPOINT_URL" 2>/dev/null || true

echo "=== RDS setup complete ==="
