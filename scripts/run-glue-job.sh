#!/usr/bin/env bash
set -euo pipefail

# ------------------------------------------------------------------
# Script: run-glue-job.sh
# Description:
#   Run the EMP-to-S3 Glue job either via the Glue runner Docker
#   container (--mode docker) or via the AWS Glue API (--mode aws).
#
#   Used standalone or from OpenTofu via terraform_data.local-exec.
# ------------------------------------------------------------------

usage() {
  cat <<EOF
Usage: $0 [options]

Modes:
  --mode docker          Run inside the glue-runner Docker container (local dev)
  --mode aws             Trigger via aws glue start-job-run (default)

Options (all modes):
  --project-dir PATH     Project root directory (default: parent of scripts/)
  --help                 Show this message

Options (--mode docker):
  --job-jar   PATH       Path to the assembly JAR     (default: auto-detect)
  --jdbc-url  URL        PostgreSQL JDBC URL           (default: jdbc:postgresql://postgres:5432/postgres)
  --jdbc-user USER       PostgreSQL user               (default: admin)
  --jdbc-pass PASS       PostgreSQL password           (default: secret123)
  --query     SQL        SQL query                     (default: SELECT ... FROM public.emp ...)
  --output    S3_PATH    S3 output path                (default: s3a://emp-output/data/)

Options (--mode aws):
  --job-name  NAME       Glue job name                 (env: GLUE_JOB_NAME)
  --region    REGION     AWS region                    (env: AWS_REGION, default: us-east-1)
  --endpoint-url URL     AWS endpoint                  (env: AWS_ENDPOINT_URL)
  --arguments "K=V ..."  Extra job parameters
  --wait                 Wait for job completion

Examples:
  # Local dev: run inside the already-running glue-runner container
  $0 --mode docker

  # AWS / Floci: trigger via API
  $0 --mode aws --job-name emp-glue-job-emp-to-s3 --wait
  $0 --mode aws --job-name emp-glue-job-emp-to-s3 --endpoint-url http://localhost:4566 --wait
EOF
  exit 1
}

# ---- Resolve project root ----
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---- Defaults ----
MODE="aws"
JOB_JAR=""
JDBC_URL="jdbc:postgresql://postgres:5432/postgres"
JDBC_USER="admin"
JDBC_PASS="secret123"
JDBC_QUERY="SELECT emp_id, emp_name, department, salary, hire_date FROM public.emp ORDER BY emp_id"
OUTPUT_PATH="s3a://emp-output/data/"

JOB_NAME="${GLUE_JOB_NAME:-}"
REGION="${AWS_REGION:-us-east-1}"
ENDPOINT_URL="${AWS_ENDPOINT_URL:-}"
WAIT=false
EXTRA_ARGS=""

# ---- Parse CLI ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)          MODE="$2";           shift 2 ;;
    --project-dir)   PROJECT_DIR="$2";    shift 2 ;;
    --job-jar)       JOB_JAR="$2";        shift 2 ;;
    --jdbc-url)      JDBC_URL="$2";       shift 2 ;;
    --jdbc-user)     JDBC_USER="$2";      shift 2 ;;
    --jdbc-pass)     JDBC_PASS="$2";      shift 2 ;;
    --query)         JDBC_QUERY="$2";     shift 2 ;;
    --output)        OUTPUT_PATH="$2";    shift 2 ;;
    --job-name)      JOB_NAME="$2";       shift 2 ;;
    --region)        REGION="$2";         shift 2 ;;
    --endpoint-url)  ENDPOINT_URL="$2";   shift 2 ;;
    --arguments)     EXTRA_ARGS="$2";     shift 2 ;;
    --wait)          WAIT=true;           shift   ;;
    --help)          usage                        ;;
    *) echo "Unknown option: $1"; usage           ;;
  esac
done

# ==================================================================
# MODE: docker  — run via Glue runner container
# ==================================================================
if [[ "$MODE" == "docker" ]]; then

  # Auto-detect fat JAR
  if [[ -z "$JOB_JAR" ]]; then
    JOB_JAR=$(find "$PROJECT_DIR/glue-job/target" -name "*-assembly-*.jar" -path "*scala-2.12*" ! -path "*/project/*" 2>/dev/null | head -1)
    if [[ -z "$JOB_JAR" ]]; then
      echo "ERROR: fat JAR not found. Build it with: cd glue-job && sbt assembly"
      exit 1
    fi
  fi
  if [[ ! -f "$JOB_JAR" ]]; then
    echo "ERROR: JAR not found at $JOB_JAR"
    exit 1
  fi

  echo "=== Running Glue job inside glue-runner container ==="
  echo "  JAR:       $JOB_JAR"
  echo "  JDBC URL:  $JDBC_URL"
  echo "  Output:    $OUTPUT_PATH"

  docker exec glue-runner bash /opt/glue-runner.sh

  echo "SUCCESS: Glue job completed in container"
  exit 0
fi

# ==================================================================
# MODE: aws  — trigger via AWS Glue API (real AWS or Floci)
# ==================================================================
if [[ "$MODE" != "aws" ]]; then
  echo "ERROR: unknown mode '$MODE'. Use --mode docker or --mode aws"
  usage
fi

if [[ -z "$JOB_NAME" ]]; then
  echo "ERROR: --job-name (or GLUE_JOB_NAME env) is required in --mode aws"
  usage
fi

AWS_ARGS=(--region "$REGION")
if [[ -n "$ENDPOINT_URL" ]]; then
  AWS_ARGS+=(--endpoint-url "$ENDPOINT_URL")
fi

echo "=== Starting Glue job via API: $JOB_NAME ==="

JOB_ARGS=()
if [[ -n "$EXTRA_ARGS" ]]; then
  JOB_ARGS+=(--arguments)
  FIRST=true
  for pair in $EXTRA_ARGS; do
    if $FIRST; then
      JOB_ARGS+=("$pair")
      FIRST=false
    else
      JOB_ARGS+=(",$pair")
    fi
  done
fi

RUN_ID=$(aws glue start-job-run \
  "${AWS_ARGS[@]}" \
  --job-name "$JOB_NAME" \
  "${JOB_ARGS[@]}" \
  --query 'JobRunId' --output text)

echo "Job run started: $RUN_ID"

if [[ "$WAIT" != true ]]; then
  echo "Job run ID: $RUN_ID"
  exit 0
fi

echo "=== Waiting for job to complete (polling every 15s) ==="

STATUS="RUNNING"
while [[ "$STATUS" == "RUNNING" || "$STATUS" == "WAITING" ]]; do
  sleep 15
  STATUS=$(aws glue get-job-run \
    "${AWS_ARGS[@]}" \
    --job-name "$JOB_NAME" \
    --run-id "$RUN_ID" \
    --query 'JobRun.JobRunState' --output text)
  echo "  Status: $STATUS"
done

case "$STATUS" in
  SUCCEEDED)
    echo "SUCCESS: Glue job $JOB_NAME ($RUN_ID) completed"
    exit 0
    ;;
  FAILED|ERROR|STOPPED|TIMEOUT)
    echo "FAILED: Glue job $JOB_NAME ($RUN_ID) ended with status: $STATUS"
    ERROR_MSG=$(aws glue get-job-run \
      "${AWS_ARGS[@]}" \
      --job-name "$JOB_NAME" \
      --run-id "$RUN_ID" \
      --query 'JobRun.ErrorMessage' --output text)
    [[ -n "$ERROR_MSG" && "$ERROR_MSG" != "None" ]] && echo "  Error: $ERROR_MSG"
    exit 1
    ;;
  *)
    echo "UNKNOWN: status $STATUS"
    exit 2
    ;;
esac
