#!/usr/bin/env bash
set -euo pipefail

FLOCI_ENDPOINT="http://localhost:4566"
AWS_REGION="us-east-1"
JDBC_USER="admin"
JDBC_PASSWORD="secret123"
SQL_QUERY="SELECT customer_id, full_name, annual_income_usd, customer_segment, domicile_currency, join_date FROM public.investors ORDER BY customer_id"
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== Starting FLOCI ==="
docker compose up -d floci

echo "=== Waiting for FLOCI to be ready ==="
for i in $(seq 1 30); do
  if curl -s "$FLOCI_ENDPOINT/_localstack/health" > /dev/null 2>&1; then
    echo "FLOCI is ready!"
    break
  fi
  echo "  Waiting... ($i/30)"
  sleep 2
done

echo "=== Creating RDS PostgreSQL instance via FLOCI ==="
aws rds create-db-instance \
  --db-instance-identifier emp-db \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --master-username "$JDBC_USER" \
  --master-user-password "$JDBC_PASSWORD" \
  --allocated-storage 20 \
  --endpoint-url "$FLOCI_ENDPOINT" \
  --region "$AWS_REGION" 2>/dev/null || true

echo "=== Waiting for RDS instance to be available ==="
for i in $(seq 1 30); do
  RDS_INFO=$(aws rds describe-db-instances \
    --db-instance-identifier emp-db \
    --endpoint-url "$FLOCI_ENDPOINT" \
    --region "$AWS_REGION" \
    --query 'DBInstances[0].Endpoint' 2>/dev/null || true)
  if [ -n "$RDS_INFO" ] && [ "$RDS_INFO" != "null" ]; then
    echo "RDS instance available!"
    break
  fi
  echo "  Waiting... ($i/30)"
  sleep 2
done

RDS_HOST=$(echo "$RDS_INFO" | jq -r '.Address // "localhost"')
RDS_PORT=$(echo "$RDS_INFO" | jq -r '.Port // "7001"')
JDBC_URL="jdbc:postgresql://${RDS_HOST}:${RDS_PORT}/postgres"
echo "RDS JDBC URL: $JDBC_URL"

echo "=== Seeding database via SQL files ==="
sleep 5
PGPASSWORD="$JDBC_PASSWORD" psql -h "$RDS_HOST" -p "$RDS_PORT" -U "$JDBC_USER" -d postgres \
  -f "$SCRIPT_DIR/db/01-schema.sql"
PGPASSWORD="$JDBC_PASSWORD" psql -h "$RDS_HOST" -p "$RDS_PORT" -U "$JDBC_USER" -d postgres \
  -f "$SCRIPT_DIR/db/02-data.sql"
echo "Database seeded from SQL files."

echo "=== Creating Glue database and table in FLOCI Data Catalog ==="
aws glue create-database \
  --database-input '{"Name": "analytics"}' \
  --endpoint-url "$FLOCI_ENDPOINT" \
  --region "$AWS_REGION" 2>/dev/null || true

aws glue create-table \
  --database-name analytics \
  --table-input '{
    "Name": "investors",
    "StorageDescriptor": {
      "Location": "s3://investor-input/data/",
      "InputFormat": "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat",
      "OutputFormat": "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat",
      "SerdeInfo": {
        "SerializationLibrary": "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"
      },
      "Columns": [
        {"Name": "customer_id",       "Type": "int"},
        {"Name": "full_name",         "Type": "string"},
        {"Name": "annual_income_usd", "Type": "double"},
        {"Name": "customer_segment",  "Type": "string"},
        {"Name": "domicile_currency", "Type": "string"},
        {"Name": "join_date",         "Type": "date"}
      ]
    }
  }' \
  --endpoint-url "$FLOCI_ENDPOINT" \
  --region "$AWS_REGION" 2>/dev/null || true

echo "=== Creating S3 buckets in FLOCI ==="
aws s3 mb s3://investor-input  --endpoint-url "$FLOCI_ENDPOINT" 2>/dev/null || true
aws s3 mb s3://investor-output --endpoint-url "$FLOCI_ENDPOINT" 2>/dev/null || true

cat <<EOF

=========================================
  FLOCI Environment Ready
=========================================
  FLOCI endpoint:   $FLOCI_ENDPOINT
  JDBC URL:         $JDBC_URL
  JDBC user:        $JDBC_USER
  JDBC password:    $JDBC_PASSWORD
  SQL query:        $SQL_QUERY
  Glue database:    analytics
  Glue table:       investors
  S3 input bucket:  investor-input
  S3 output bucket: investor-output
=========================================

Run locally:   docker compose up rds-setup glue-runner
Deploy to AWS: tofu -chdir=infra apply
EOF
