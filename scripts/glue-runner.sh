#!/usr/bin/env bash
set -euo pipefail

echo "=== Running Glue job ==="
export JAVA_TOOL_OPTIONS="-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider"
exec spark-submit \
  --class com.example.Main \
  --master local[*] \
  --conf "spark.hadoop.fs.s3a.endpoint=$AWS_ENDPOINT_URL" \
  --conf spark.hadoop.fs.s3a.path.style.access=true \
  --conf spark.hadoop.fs.s3a.connection.ssl.enabled=false \
  --conf 'spark.hadoop.fs.s3a.aws.credentials.provider=org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider' \
  --conf spark.hadoop.fs.s3a.access.key=test \
  --conf spark.hadoop.fs.s3a.secret.key=test \
  --conf spark.hadoop.fs.s3a.committer.name=directory \
  /opt/job.jar \
  jdbc:postgresql://postgres:5432/postgres \
  admin \
  secret123 \
  'SELECT emp_id, emp_name, department, salary, hire_date FROM public.emp ORDER BY emp_id' \
  s3a://emp-output/data/ \
  "${WIREMOCK_ENDPOINT:-http://wiremock:8080}"
