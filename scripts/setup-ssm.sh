#!/usr/bin/env bash
set -euo pipefail

# Quick helper to ensure SSM params and SQS queue exist in Floci.
# OpenTofu also creates these; this script is for manual testing.

FLOCI_HOST="${1:-localhost}"
FLOCI_PORT="${2:-4566}"
ENDPOINT_URL="http://${FLOCI_HOST}:${FLOCI_PORT}"
AWS_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

echo "=== Creating SSM parameters ==="
for param in \
  "/emp/api/endpoint=http://wiremock:8080" \
  "/emp/api/employee-check-enabled=true" \
  "/emp/sqs/queue-url=http://localhost:4566/000000000000/emp-processing"; do
  name="${param%%=*}"
  value="${param#*=}"
  aws ssm put-parameter \
    --name "$name" --value "$value" --type String --overwrite \
    --endpoint-url "$ENDPOINT_URL" --region "$AWS_REGION" > /dev/null 2>&1 || true
done

echo "=== Creating SQS queue ==="
aws sqs create-queue --queue-name emp-processing \
  --endpoint-url "$ENDPOINT_URL" --region "$AWS_REGION" > /dev/null 2>&1 || true

SQS_URL=$(aws sqs get-queue-url --queue-name emp-processing \
  --endpoint-url "$ENDPOINT_URL" --region "$AWS_REGION" \
  --query 'QueueUrl' --output text)
echo "SQS queue URL: $SQS_URL"

echo "=== Verifying SSM parameters ==="
aws ssm get-parameters \
  --names "/emp/api/endpoint" "/emp/api/employee-check-enabled" "/emp/sqs/queue-url" \
  --endpoint-url "$ENDPOINT_URL" --region "$AWS_REGION" \
  --query "Parameters[].{Name:Name,Value:Value}" --output table

echo "=== Setup complete ==="
