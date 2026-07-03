#!/usr/bin/env bash
set -euo pipefail

FLOCI_ENDPOINT="http://localhost:4566"
AWS_REGION="us-east-1"
JDBC_USER="admin"
JDBC_PASSWORD="secret123"
SQL_QUERY="SELECT emp_id, emp_name, department, salary, hire_date FROM public.emp ORDER BY emp_id"
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
  -f "$SCRIPT_DIR/glue-job/sql/schema.sql"
PGPASSWORD="$JDBC_PASSWORD" psql -h "$RDS_HOST" -p "$RDS_PORT" -U "$JDBC_USER" -d postgres \
  -f "$SCRIPT_DIR/glue-job/sql/data.sql"
echo "Database seeded from SQL files."

echo "=== Creating Glue database and table in FLOCI Data Catalog ==="
aws glue create-database \
  --database-input '{"Name": "analytics"}' \
  --endpoint-url "$FLOCI_ENDPOINT" \
  --region "$AWS_REGION" 2>/dev/null || true

aws glue create-table \
  --database-name analytics \
  --table-input '{
    "Name": "emp",
    "StorageDescriptor": {
      "Location": "s3://emp-input/data/",
      "InputFormat": "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat",
      "OutputFormat": "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat",
      "SerdeInfo": {
        "SerializationLibrary": "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"
      },
      "Columns": [
        {"Name": "emp_id",     "Type": "int"},
        {"Name": "emp_name",   "Type": "string"},
        {"Name": "department", "Type": "string"},
        {"Name": "salary",     "Type": "double"},
        {"Name": "hire_date",  "Type": "date"}
      ]
    }
  }' \
  --endpoint-url "$FLOCI_ENDPOINT" \
  --region "$AWS_REGION" 2>/dev/null || true

echo "=== Creating S3 buckets in FLOCI ==="
aws s3 mb s3://emp-input  --endpoint-url "$FLOCI_ENDPOINT" 2>/dev/null || true
aws s3 mb s3://emp-output --endpoint-url "$FLOCI_ENDPOINT" 2>/dev/null || true

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
  Glue table:       emp
  S3 input bucket:  emp-input
  S3 output bucket: emp-output
=========================================

Run locally:   docker compose up rds-setup glue-runner
Deploy to AWS: tofu -chdir=infra apply
EOF
