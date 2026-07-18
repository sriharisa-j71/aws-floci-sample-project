#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# shellcheck source=/dev/null
source "$ROOT_DIR/.floci.env"

run_aws() {
  if command -v aws &>/dev/null; then
    aws "$@"
  else
    docker compose -f "$ROOT_DIR/docker-compose.yml" run --rm --no-deps \
      -e AWS_ENDPOINT_URL="$AWS_ENDPOINT_URL" \
      -e AWS_DEFAULT_REGION="$AWS_DEFAULT_REGION" \
      -e AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
      -e AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
      infra-setup aws "$@"
  fi
}

wait_for_floci() {
  echo "Waiting for floci to be healthy..."
  local retries=30
  while [ $retries -gt 0 ]; do
    if curl -sf http://localhost:4566/_localstack/health &>/dev/null; then
      echo "floci is ready."
      return 0
    fi
    retries=$((retries - 1))
    sleep 2
  done
  echo "ERROR: floci did not become healthy in time."
  exit 1
}

upload_lambda() {
  local local_path="$1"
  local s3_key="$2"
  echo "Uploading $local_path -> s3://glue-artifacts/$s3_key"
  run_aws s3 cp "$local_path" "s3://glue-artifacts/$s3_key"
}

create_or_update_lambda() {
  local func_name="$1"
  local runtime="$2"
  local handler="$3"
  local code_arg="$4"
  local timeout="$5"
  local memory="$6"
  shift 6

  local s3_bucket s3_key
  s3_bucket=$(echo "$code_arg" | sed 's/S3Bucket=\([^,]*\).*/\1/')
  s3_key=$(echo "$code_arg" | sed 's/.*S3Key=\([^,]*\).*/\1/')

  local env_file
  env_file=$(mktemp)
  printf '{"Variables":{' > "$env_file"
  local first=true
  for kv in "$@"; do
    local key="${kv%%=*}"
    local val="${kv#*=}"
    if [ "$first" = true ]; then
      first=false
    else
      printf ',' >> "$env_file"
    fi
    printf '"%s":"%s"' "$key" "$val" >> "$env_file"
  done
  printf '}}' >> "$env_file"

  if run_aws lambda get-function --function-name "$func_name" &>/dev/null; then
    echo "Updating Lambda: $func_name"
    run_aws lambda update-function-code --function-name "$func_name" \
      --s3-bucket "$s3_bucket" --s3-key "$s3_key"
  else
    echo "Creating Lambda: $func_name"
    run_aws lambda create-function \
      --function-name "$func_name" \
      --runtime "$runtime" \
      --role arn:aws:iam::000000000000:role/lambda-exec-role \
      --handler "$handler" \
      --code "$code_arg" \
      --timeout "$timeout" \
      --memory-size "$memory" \
      --environment "file://$env_file"
  fi
  rm -f "$env_file"
}

echo "=== Setting up infrastructure ==="

wait_for_floci

# Create SSM parameters
echo "=== Creating SSM params ==="
run_aws ssm put-parameter --name /investor/api/endpoint --value http://wiremock:8080 --type String --overwrite

# Create SQS queues
echo "=== Creating SQS queues ==="
DLQ_URL=$(run_aws sqs create-queue --queue-name investor-processing-dlq --query QueueUrl --output text)
DLQ_ARN=$(run_aws sqs get-queue-attributes --queue-url "$DLQ_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)
MAIN_URL=$(run_aws sqs create-queue --queue-name investor-processing --query QueueUrl --output text)

REDRIVE_POLICY=$(python3 -c "import json,sys; policy=json.dumps({'deadLetterTargetArn':sys.argv[1],'maxReceiveCount':3}); print(json.dumps({'RedrivePolicy':policy}))" "$DLQ_ARN")
run_aws sqs set-queue-attributes --queue-url "$MAIN_URL" --attributes "$REDRIVE_POLICY"
run_aws ssm put-parameter --name /investor/sqs/queue-url --value "$MAIN_URL" --type String --overwrite

# Create S3 buckets
echo "=== Creating S3 buckets ==="
run_aws s3 mb s3://glue-artifacts --region us-east-1 2>/dev/null || true
run_aws s3 mb s3://investor-output --region us-east-1 2>/dev/null || true
run_aws s3 mb s3://currency-rates-input --region us-east-1 2>/dev/null || true

# Upload Lambda artifacts
echo "=== Uploading Lambda JARs to S3 ==="
upload_lambda "$ROOT_DIR/lambda-investor-file-handler/target/investor-file-handler-1.0.jar" "lambdas/investor-file-handler-1.0.jar"
upload_lambda "$ROOT_DIR/lambda-investor-sal-processor/target/investor-sal-processor-1.0.jar" "lambdas/investor-sal-processor-1.0.jar"
upload_lambda "$ROOT_DIR/lambda-currency-refresh/bootstrap" "lambdas/currency-refresh-bootstrap"
upload_lambda "$ROOT_DIR/lambda-risk-score-calculator/risk-score-calculator.zip" "lambdas/risk-score-calculator.zip"

# Create Lambda functions
create_or_update_lambda "investor-file-handler" "java11" "com.example.S3ToSqsLambda::handleRequest" \
  "S3Bucket=glue-artifacts,S3Key=lambdas/investor-file-handler-1.0.jar" 60 512 \
  "AWS_ENDPOINT_URL=http://floci:4566" "AWS_DEFAULT_REGION=us-east-1"

create_or_update_lambda "investor-sal-processor" "java11" "com.example.SqsProcessorLambda::handleRequest" \
  "S3Bucket=glue-artifacts,S3Key=lambdas/investor-sal-processor-1.0.jar" 60 512 \
  "AWS_ENDPOINT_URL=http://floci:4566" "AWS_DEFAULT_REGION=us-east-1"

create_or_update_lambda "currency-refresh" "provided.al2023" "bootstrap" \
  "S3Bucket=glue-artifacts,S3Key=lambdas/currency-refresh-bootstrap" 60 256 \
  "AWS_ENDPOINT_URL=http://floci:4566" "AWS_DEFAULT_REGION=us-east-1" "DB_DSN=postgres://admin:secret123@postgres:5432/postgres?sslmode=disable"

create_or_update_lambda "risk-score-calculator" "provided.al2023" "bootstrap" \
  "S3Bucket=glue-artifacts,S3Key=lambdas/risk-score-calculator.zip" 120 512 \
  "AWS_ENDPOINT_URL=http://floci:4566" "AWS_DEFAULT_REGION=us-east-1" "DB_DSN=postgres://admin:secret123@postgres:5432/postgres?sslmode=disable"

# Set up S3 notifications
echo "=== Setting up S3 notification (investor-output -> investor-file-handler) ==="
run_aws s3api put-bucket-notification-configuration --bucket investor-output --notification-configuration '{"LambdaFunctionConfigurations":[{"LambdaFunctionArn":"arn:aws:lambda:us-east-1:000000000000:function:investor-file-handler","Events":["s3:ObjectCreated:*"],"Filter":{"Key":{"FilterRules":[{"Name":"suffix","Value":".csv"}]}}}]}'

echo "=== Setting up S3 notification (currency-rates-input -> currency-refresh + risk-score-calculator) ==="
run_aws s3api put-bucket-notification-configuration --bucket currency-rates-input --notification-configuration '{"LambdaFunctionConfigurations":[{"LambdaFunctionArn":"arn:aws:lambda:us-east-1:000000000000:function:currency-refresh","Events":["s3:ObjectCreated:*"]},{"LambdaFunctionArn":"arn:aws:lambda:us-east-1:000000000000:function:risk-score-calculator","Events":["s3:ObjectCreated:*"]}]}'

# Set up SQS event source mapping
echo "=== Setting up SQS event mapping (investor-processing -> investor-sal-processor) ==="
run_aws lambda create-event-source-mapping \
  --function-name investor-sal-processor \
  --event-source-arn "arn:aws:sqs:us-east-1:000000000000:investor-processing" \
  --batch-size 10 \
  --function-response-types ReportBatchItemFailures 2>/dev/null || true

echo "=== Infra setup complete ==="