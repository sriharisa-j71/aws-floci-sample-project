# FLOCI Glue Job — Employee Processing Pipeline

An end-to-end employee data processing pipeline using AWS Glue (Spark), Lambda (Java 17), SQS, SSM, S3, and a WireMock API mock — all runnable locally via [Floci](https://floci.dev/) or deployed to real AWS via OpenTofu.

## Architecture

```
┌──────────────┐     ┌──────────────┐     ┌──────────┐     ┌──────────────────────┐     ┌──────────┐     ┌────────────────────────┐
│  PostgreSQL   │     │  Glue Job    │     │    S3     │     │  Lambda 1            │     │   SQS    │     │  Lambda 2              │
│  (emp table)  │────►│  (Spark)     │────►│  emp-out  │────►│  employee-file-      │────►│  emp-    │────►│  employee-sal-         │
│  :5432        │     │  :15000      │     │  /data/   │     │  handler             │     │processing│     │  processor             │
└──────┬───────┘     └──────┬───────┘     └──────────┘     │  reads CSV, pub       │     └──────────┘     │  parses msg, calls API │
       │                    │                               │  1 msg/employee       │                      └────────┬───────────────┘
       │ JDBC read          │                               └──────────┬───────────┘                               │
       │                    │                                          │                                           │
       │                    │ GET /employee/{id}                       │ GET /employee/{id}                        │
       │                    │                                          │                                           │
       │                    └─────┐                               ┌─────┘                                           │
       │                          │                               │                                                 │
       │                     ┌────▼───────────────────────────────▼─────────────────────────────────────────────────▼──┐
       │                     │                         WireMock :8080                                                  │
       │                     │  GET /employee/1 ──► {"exists":true,"emp_id":1,"emp_name":"Alice Johnson",...}            │
       │                     │  GET /employee/99 ► {"exists":false}                                                     │
       │                     └─────────────────────────────────────────────────────────────────────────────────────────┘
       │
       │                    ┌──────────────────────────────────────────────────┐
       └────────────────────│  SSM Parameter Store                             │
                            │  /emp/api/endpoint         http://wiremock:8080 │
                            │  /emp/api/employee-check-enabled     true       │
                            │  /emp/sqs/queue-url  http://floci:4566/.../emp  │
                            └──────────────────────────────────────────────────┘
```

1. **Glue Job** reads `emp` table from PostgreSQL via JDBC, calls `GET /employee/{id}` on WireMock for each row, writes pipe-delimited CSV to S3.
2. **Lambda `employee-file-handler`** is auto-triggered by Floci on S3 `ObjectCreated:*` — reads CSV from S3, parses employee records, publishes one SQS message per employee.
3. **Lambda `employee-sal-processor`** is auto-triggered by Floci on SQS messages — parses message, calls `GET /employee/{id}` on WireMock, returns processed result.
4. **SSM Parameter Store** holds `/emp/api/endpoint`, `/emp/api/employee-check-enabled`, and `/emp/sqs/queue-url` — read by Glue and Lambdas at startup.
5. All infrastructure (IAM, S3, SQS, SSM, Glue Job, Lambda functions) is defined in OpenTofu.

## Prerequisites

- Docker & Docker Compose
- Java JDK 17 (Corretto or OpenJDK)
- Scala CLI or SBT (for Glue job assembly)
- OpenTofu (optional, for infra management)

## Project Structure

```
├── docker-compose.yml                   # Local services: floci, wiremock, postgres, glue-runner, infra-setup
├── Dockerfile                            # Glue runner Docker image (glue-scala-minimal)
├── glue-job/                             # Scala Glue Spark job
│   ├── build.sbt
│   └── src/main/scala/com/example/
│       ├── Main.scala                    # Entry point
│       └── EmpToS3Core.scala             # Core logic: JDBC read, WireMock API, CSV write
├── lambda-employee-file-handler/         # Java 17 Lambda: S3 event → SQS
│   ├── pom.xml
│   └── src/main/java/com/example/
│       ├── S3ToSqsLambda.java
│       └── EmployeeRecord.java
├── lambda-employee-sal-processor/        # Java 17 Lambda: SQS event → WireMock API
│   ├── pom.xml
│   └── src/main/java/com/example/
│       ├── SqsProcessorLambda.java
│       └── EmployeeRecord.java
├── wiremock/
│   └── mappings/                         # WireMock stub mappings (employee-check-*.json)
├── infra/                                # OpenTofu infrastructure
│   ├── main.tf                           # All AWS resources
│   ├── variables.tf
│   ├── outputs.tf
│   ├── floci.tfvars                      # Local dev variable overrides
│   └── terraform.tf                      # Provider config
├── scripts/                              # Helper scripts
│   ├── glue-runner.sh                    # spark-submit inside container
│   ├── run-glue-job.sh                   # On-demand Glue job submitter (docker exec or AWS API)
│   └── build-lambdas.sh                  # Build both Lambda JARs
├── data/                                 # Local data (Floci S3 storage)
├── .gitignore
└── .dockerignore
```

## Quick Start

### 1. Build Artifacts

```bash
# Glue job JAR
cd glue-job && sbt assembly && cd ..

# Lambda JARs
bash scripts/build-lambdas.sh
```

Outputs:
- `glue-job/target/out/jvm/scala-2.12.18/glue5-spark-job/glue5-spark-job-assembly-1.0.jar`
- `lambda-employee-file-handler/target/employee-file-handler-1.0.jar`
- `lambda-employee-sal-processor/target/employee-sal-processor-1.0.jar`

### 2. Build Glue Runner Docker Image

```bash
docker build -t glue-scala-minimal:latest .
```

### 3. Start All Services

```bash
docker compose up -d
```

This starts:
- **floci** — AWS API mock on `:4566`
- **postgres** — Employee database on `:5432`
- **wiremock** — Employee check API on `:8080`
- **infra-setup** — One-shot container that creates SSM params, SQS queue, S3 buckets, Lambda functions, S3 notification, and SQS event mapping in Floci
- **glue-runner** — Spark environment, stays alive waiting for job submissions

### 4. Submit the Glue Job

```bash
bash scripts/run-glue-job.sh --mode docker
```

This runs `spark-submit` inside the `glue-runner` container via `docker exec`. The Glue job reads from PostgreSQL, calls WireMock per employee, and writes CSV to Floci S3 (`emp-output/data/`).

### 5. Verify End-to-End

```bash
# Check WireMock request count (should show 10: 5 from Glue + 5 from Lambda 2)
curl http://localhost:8080/__admin/requests | jq '.requests | length'

# Check Floci logs for Lambda invocations
docker logs floci 2>&1 | grep -E '(employee-file-handler|employee-sal-processor)'
```

The entire flow runs automatically:
1. Glue job writes CSV to S3
2. Floci detects S3 `ObjectCreated:*` and triggers **employee-file-handler** Lambda
3. Lambda publishes 5 SQS messages
4. Floci's SQS event source mapping detects messages and triggers **employee-sal-processor** Lambda
5. Lambda calls WireMock API for each employee

No manual Lambda trigger scripts are needed — Floci handles all event source mappings natively.

## How It Works Locally

Floci (based on LocalStack) natively supports:
- **S3 bucket notifications** → Lambda invocation (when a CSV is written to `emp-output/`)
- **SQS event source mappings** → Lambda invocation (when messages arrive in `emp-processing`)

The `infra-setup` container configures all of this at startup via AWS CLI commands. The Lambda JARs are uploaded to Floci's S3 and registered as Lambda functions. When events fire, Floci launches short-lived Docker containers using `public.ecr.aws/lambda/java:17` to execute the Java handlers.

## Local Testing with OpenTofu

```bash
cd infra
tofu init
tofu plan -var-file=floci.tfvars
tofu apply -var-file=floci.tfvars
```

The `floci.tfvars` file sets `glue_trigger_mode = "docker"`. Resources like Lambda functions and Glue Job (CreateJob) are gated behind `local.is_aws` — they are **not created when using Floci** since the `infra-setup` container handles local provisioning.

## Deploying to Real AWS

1. Create `infra/aws.tfvars`:
```hcl
glue_trigger_mode  = "aws"
aws_region         = "us-east-1"
aws_access_key_id  = "<your-key>"
aws_secret_access_key = "<your-secret>"
```

2. Deploy:
```bash
cd infra
tofu plan -var-file=aws.tfvars -out=tfplan
tofu apply tfplan
```

This creates:
- S3 buckets (artifacts + output)
- IAM roles & policies (Glue, Lambdas)
- Glue Job + auto-triggered StartJobRun
- Lambda functions (`employee-file-handler`, `employee-sal-processor`)
- S3 bucket notification (S3 → Lambda 1)
- SQS queue + event source mapping (SQS → Lambda 2)
- SSM parameters

## Helper Scripts

| Script | Purpose |
|---|---|
| `scripts/build-lambdas.sh` | Build both Lambda JARs with Maven |
| `scripts/glue-runner.sh` | spark-submit wrapper inside the glue-runner container |
| `scripts/run-glue-job.sh` | On-demand Glue job submitter (docker exec or AWS API) |

## Configuration

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `AWS_ENDPOINT_URL` | — | S3 endpoint (set to `http://floci:4566` for local) |
| `WIREMOCK_ENDPOINT` | — | WireMock base URL (set to `http://wiremock:8080` for local) |
| `AWS_ACCESS_KEY_ID` | `test` | AWS credential for local Floci |
| `AWS_SECRET_ACCESS_KEY` | `test` | AWS credential for local Floci |
| `AWS_DEFAULT_REGION` | `us-east-1` | AWS region |

### SSM Parameters

| Name | Value |
|---|---|
| `/emp/api/endpoint` | `http://wiremock:8080` |
| `/emp/api/employee-check-enabled` | `true` |
| `/emp/sqs/queue-url` | `http://localhost:4566/000000000000/emp-processing` |

### WireMock Employee Check API

```
GET /employee/{id}
```

**Response:**
```json
{ "exists": true, "emp_id": 1, "emp_name": "Alice Johnson", "department": "Engineering" }
```

## Notes

- **Floci storage mode** is `memory` — data is lost on container restart. Switch to `hybrid` for persistence: set `FLOCI_STORAGE_MODE: hybrid` in docker-compose.yml.
- The Glue job uses `coalesce(1)` so the `foreach` API call runs on a single executor.
- No JDK 21 available in this environment — Lambdas use JDK 17.
- Floci executes Java Lambdas by launching Docker containers using the `public.ecr.aws/lambda/java:17` runtime image.
- The `glue-runner` container stays alive via `sleep infinity`; submit jobs on demand with `docker exec` or `scripts/run-glue-job.sh --mode docker`.
