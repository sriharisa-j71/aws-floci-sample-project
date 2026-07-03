# FLOCI Glue Job — Investment Bank Customer/Investor Simulation

End-to-end investment bank customer simulation pipeline using AWS Glue (Spark), Lambda (Java 17 + Go), SQS, SSM, S3, and WireMock API mock — runnable locally via [Floci](https://floci.dev/) or deployed to real AWS via OpenTofu.

**Business Context**: Banks need to simulate large-scale investor populations with realistic financial profiles — dual-currency holdings (USD + domicile), temporal risk/investment/liability histories, multi-member family banking relationships (~30% share addresses/surnames) — to test risk aggregation, portfolio analytics, and regulatory reporting pipelines. This project generates synthetic investors, processes them through a Spark ETL enriched by microservice API calls, and validates outputs via async Lambda/SQS chains.

## Architecture

```mermaid
flowchart LR
  subgraph Input["Data Sources"]
    PG[("PostgreSQL\n6 tables\ninvestors, personal_details\nrisk_profiles, investments\nliabilities, currency_rates")]
    CUR_FILE["Currency Rates File\npipe-delimited CSV"]
  end

  subgraph Compute["Compute & Processing"]
    GEN["Go: generate-data\nsynthetic investor gen"]
    GLUE["Glue Job (Spark)\nInvestorToS3Core\nJDBC read + API enrich"]
    L1["Lambda: investor-file-handler\nJava 17\nS3Event → SQS"]
    L2["Lambda: investor-sal-processor\nJava 17\nSQS → WireMock check"]
    L3["Lambda: currency-refresh\nGo (provided.al2023)\nS3 → pgx batch upsert"]
  end

  subgraph Storage["Storage & Messaging"]
    S3_IN[("S3: investor-output\npipe-delimited CSV")]
    S3_CUR[("S3: currency-rates-input")]
    SQS[("SQS: investor-processing\n1 msg / investor row")]
    DLQ[("SQS: DLQ\nunknown investors")]
    SSM[("SSM Parameter Store\n/investor/api/endpoint\n/investor/sqs/queue-url")]
  end

  subgraph MockAPI["Mock API"]
    WM["WireMock :8080\nGET /investor/{id}\nGET /segment/{segment}"]
  end

  PG -->|JDBC SELECT| GLUE
  GEN -->|bulk insert| PG
  CUR_FILE -->|S3 Put| S3_CUR
  S3_CUR -->|ObjectCreated:*| L3
  L3 -->|truncate + batch INSERT| PG
  
  GLUE -->|GET /segment/{segment}| WM
  GLUE -->|pipe-delimited CSV| S3_IN
  S3_IN -->|ObjectCreated:*.csv| L1
  L1 -->|parse CSV, 1 msg/row| SQS
  SQS -->|SQSEvent| L2
  L2 -->|GET /investor/{id}| WM
  L2 -->|exists:false| DLQ
  
  L1 -.->|read at init| SSM
  L2 -.->|read at init| SSM
  GLUE -.->|read at init| SSM
```

**Flow**:
1. **Data Generation**: `generate-data` Go tool inserts N synthetic investors with family groups, temporal risk profiles, investments, liabilities into PostgreSQL
2. **Currency Refresh**: Upload pipe-delimited rates file to `currency-rates-input` S3 bucket → triggers Go Lambda → truncates & reloads `currency_rates` table
3. **Spark ETL**: Glue Job reads investors by segment from PostgreSQL via JDBC, calls `GET /segment/{segment}` on WireMock once per segment (not per row), writes pipe-delimited CSV to S3
4. **Async Validation**: Floci detects S3 `ObjectCreated:*` → invokes `investor-file-handler` Lambda → parses CSV → publishes 1 SQS msg/investor → `investor-sal-processor` Lambda calls `GET /investor/{id}` → unknown investors (IDs >5) routed to DLQ

## Components

### PostgreSQL — Investor Database (`db/`)

6 tables with temporal columns and CHECK constraints. All amounts in **dual currency** (USD + domicile).

| Table | Temporal? | Description |
|---|---|---|
| `investors` | No | Core customer data: name, income, segment, currency, bank accounts (JSONB) |
| `personal_details` | No | 1:1 linked: email, phone, DOB, gender, employment, address (JSONB) |
| `risk_profiles` | Yes | Risk tier + score 1-100, effective_start/end_date, is_active |
| `investments` | Yes | 8 types, quantity + dual-currency values, valuation_date |
| `liabilities` | Yes | 7 types, dual-currency amounts, interest_rate, monthly_payment |
| `currency_rates` | No | base → target rate table, truncated & reloaded on each file upload |

Seed data: 5 investors (Alice Johnson Wealth/USD, Bob Smith Premium/USD, Carol Davis Wealth/CHF, Dave Wilson Retail/USD, Eve Martin Corporate/GBP) + 5 currency rates.

### Java 17 Lambdas (`lambda-investor-*/`)

| Lambda | Trigger | Logic |
|---|---|---|
| `investor-file-handler` | S3 `ObjectCreated:*` on `investor-output/` (*.csv) | Reads CSV from S3, parses pipe-delimited rows into `InvestorRecord`, publishes 1 JSON SQS message per row to `investor-processing` |
| `investor-sal-processor` | SQS event from `investor-processing` | Deserializes each message, calls `GET /investor/{id}` on WireMock, returns `BatchItemFailure` for non-existent investors → DLQ with `maxReceiveCount=3` |

### Go Lambda — Currency Refresh (`lambda-currency-refresh/`)

**Runtime**: `provided.al2023`  
**Trigger**: S3 `ObjectCreated:*` on `currency-rates-input/`  
**Logic**: Downloads file from S3, parses pipe-delimited rows (`base_currency|target_currency|rate|effective_date`), `TRUNCATE currency_rates`, then batch INSERTs via pgx `SendBatch`. Batch sizes configurable via env vars `VALUES_PER_STMT` (default 25) and `STMTS_PER_BATCH` (default 10). UPX-compressed binary (~13MB).

### Spark Glue Job (`glue-job/`)

`Main.scala` → `InvestorToS3Core.scala`:
- Reads investors by segment via JDBC: `SELECT ... FROM investors WHERE customer_segment = '<segment>'`
- Resolves `/segment/{segment}` API endpoint from SSM or CLI arg
- Calls API once per unique segment (not per row)
- Adds `segment_status` JSON column from API response
- Writes single pipe-delimited CSV to `s3a://investor-output/data/`
- SBT assembly, Spark 3.5.4, Scala 2.12.18, Hadoop 3.3.5

### WireMock — API Mock (`wiremock/mappings/`)

| Endpoint | Purpose |
|---|---|
| `GET /investor/{id}` | Returns `{"exists":true,"customer_id":N,...}` for IDs 1-5, `{"exists":false}` for all others (catch-all priority 10) |
| `GET /segment/{segment}` | Returns `{"exists":true,"segment":"Wealth","status":"active"}` with response templating |

### SSM Parameter Store

| Parameter | Value |
|---|---|
| `/investor/api/endpoint` | `http://wiremock:8080` |
| `/investor/sqs/queue-url` | `http://localhost:4566/000000000000/investor-processing` |

## Tools

### `tools/generate-data/` — Synthetic Investor Generator

Go CLI tool that inserts N investors into PostgreSQL with realistic profiles.

```
Flags:
  -count int       Investors to generate (default 1000)
  -workers int     Concurrent workers (default 4)
  -family-pct float  Fraction in family groups (default 0.30)
  -max-family int    Max members per family (default 5)
  -dsn string      PostgreSQL DSN (default: localhost:5432)
```

Features:
- 5 segments with income bands: Wealth ($200K-600K), Premium ($70K-200K), Retail ($25K-80K), Corporate ($100K-1M)
- 5 currencies: USD, GBP, CHF, EUR, JPY with fixed rates
- 5 domicile currencies per segment (not just the above)
- ~30% in family groups (shared address, surname, currency, nationality, `family_group_id`)
- 1-3 temporal risk profiles per investor (25% inactive)
- 2-5 temporal investments per investor (25% inactive, 8 types)
- 0-3 temporal liabilities per investor (20% inactive, 7 types)
- Uses `SELECT MAX(customer_id)+1` to avoid conflicts with seed data
- `pgx/v5 CopyFrom` for bulk inserts in transactions
- Build: `CGO_ENABLED=0` + UPX (~4.3MB)

### `tools/generate-currency-file/` — Currency Rates File Generator

Go CLI that creates a pipe-delimited CSV of currency rates with configurable jitter.

```
Flags:
  -out string      Output file path (default "currency_rates.csv")
  -date string     Effective date YYYY-MM-DD (default: today)
  -pairs int       Number of currency pairs (default 15)
  -variance float  Max random variance factor (default 0.02)
```

Output format: `base_currency|target_currency|rate|effective_date` (6 decimal places).  
Supported currencies: USD, GBP, CHF, EUR, JPY, CAD, AUD, NZD, CNY, INR, BRL, MXN, SEK, NOK, KRW, SGD.

### `lambda-currency-refresh/` — Currency Refresh Go Lambda

Triggered by S3 Put on `currency-rates-input` bucket. Downloads file, parses pipe-delimited rows, truncates + batch inserts into `currency_rates`.

Configurable via env vars: `VALUES_PER_STMT` (rows per INSERT, default 25), `STMTS_PER_BATCH` (INSERTs per SendBatch, default 10), `DB_DSN` (PostgreSQL connection string).

## How to Use

### Prerequisites
- Docker & Docker Compose
- Java JDK 17 (Corretto or OpenJDK)
- SBT (for Glue JAR assembly)
- Go 1.21+ and UPX (for Go binaries)
- OpenTofu (optional, for AWS infra management)

### Build All Artifacts

```bash
# 1. Glue job JAR
cd glue-job && sbt assembly && cd ..

# 2. Java Lambda JARs
bash scripts/build-lambdas.sh

# 3. Go Lambda (cross-compiled, UPX-compressed)
bash lambda-currency-refresh/build.sh

# 4. Go data generator (UPX-compressed)
bash tools/generate-data/build.sh

# 5. Go currency file generator (UPX-compressed)
bash tools/generate-currency-file/build.sh

# 6. Glue runner Docker image
docker build -t glue-scala-minimal:latest .
```

### Run Locally

```bash
# Start all services
docker compose up -d

# Submit Glue job (reads Wealth segment by default)
bash scripts/run-glue-job.sh --mode docker

# Check S3 output
aws --endpoint-url http://localhost:4566 s3 ls s3://investor-output/data/
aws --endpoint-url http://localhost:4566 s3 cp s3://investor-output/data/part-00000-*.csv -

# Generate and upload currency rates
./tools/generate-currency-file/generate-currency-file -out /tmp/rates.csv
aws --endpoint-url http://localhost:4566 s3 cp /tmp/rates.csv s3://currency-rates-input/

# Verify currency rates in DB
docker exec postgres psql -U admin -d postgres -c "SELECT * FROM currency_rates;"

# Check Lambda logs
docker logs floci 2>&1 | grep -E '(investor-file-handler|investor-sal-processor|currency-refresh)'
```

### Verify End-to-End Flow

1. `seed-generator` inserts 100 investors + ~900 related rows
2. `glue-runner` writes segment-filtered CSV to S3
3. Floci triggers `investor-file-handler` Lambda → SQS
4. Floci triggers `investor-sal-processor` Lambda → seed investors (IDs 1-5) pass, generated (IDs 6-105) go to DLQ
5. Upload rates file → `currency-refresh` Lambda truncates & reloads `currency_rates`

## Scripts

### `scripts/build-lambdas.sh`
Builds both Java Lambda JARs with Maven.
```bash
bash scripts/build-lambdas.sh
# Outputs:
#   lambda-investor-file-handler/target/investor-file-handler-1.0.jar
#   lambda-investor-sal-processor/target/investor-sal-processor-1.0.jar
```

### `scripts/glue-runner.sh`
Internal spark-submit wrapper used inside the glue-runner container. Mounted at `/opt/glue-runner.sh`. Reads `$SEGMENT` and `$WIREMOCK_ENDPOINT` env vars. Not intended for direct use — invoked by `run-glue-job.sh --mode docker` or `docker exec`.

### `scripts/run-glue-job.sh`
Dual-mode Glue job trigger.
```bash
# Local dev (docker exec)
bash scripts/run-glue-job.sh --mode docker
  # Optional: --job-jar PATH   --jdbc-url URL   --jdbc-user USER
  #           --jdbc-pass PASS  --output S3_PATH  --query SQL

# AWS / Floci API
bash scripts/run-glue-job.sh --mode aws --job-name emp-glue-job-emp-to-s3 [--wait]
  # Optional: --region REGION  --endpoint-url URL  --arguments "K=V K=V"
```

### `scripts/search-logs.sh`
Log query CLI against Floci CloudWatch Logs API.
```bash
bash scripts/search-logs.sh groups                              # List log groups
bash scripts/search-logs.sh search "exists=true"                 # Search pattern
bash scripts/search-logs.sh search "ERROR" /aws/lambda/...       # Search in specific group
bash scripts/search-logs.sh errors                               # ERROR/WARN level
bash scripts/search-logs.sh recent                               # Last 10 events
bash scripts/search-logs.sh publish                              # SQS publish events (file-handler)
bash scripts/search-logs.sh investors                            # Investor API check results (sal-processor)
bash scripts/search-logs.sh tail                                 # Stream new events (poll 3s)
```

### `scripts/setup-floci.sh`
One-shot Floci infra setup: creates RDS instance, seeds schema+data, creates Glue Data Catalog database + table, creates S3 buckets.
```bash
bash scripts/setup-floci.sh
```

### `scripts/rds-setup.sh`
RDS-based PostgreSQL init: creates Floci RDS instance, waits for endpoint, seeds schema+data from SQL files.
```bash
bash scripts/rds-setup.sh [floci_host] [floci_port]
# Defaults: floci:4566
```

### `scripts/setup-ssm.sh`
Creates SSM parameters and SQS queue in Floci.
```bash
bash scripts/setup-ssm.sh [floci_host] [floci_port]
# Defaults: localhost:4566
```

### `scripts/remove-snaps.sh`
Ubuntu snap removal utility (developer convenience, unrelated to pipeline).

## Debugging

### Floci Docker Logs
```bash
# Tail all Floci Lambda invocations
docker logs floci -f 2>&1 | grep -E '(START|END|REPORT)'

# Specific Lambda logs
docker logs floci 2>&1 | grep "investor-file-handler"
docker logs floci 2>&1 | grep "investor-sal-processor"
docker logs floci 2>&1 | grep "currency-refresh"

# S3 notifications
docker logs floci 2>&1 | grep "notification"

# Lambda errors
docker logs floci 2>&1 | grep -i "error"
```

### Database Debugging
```bash
# Query investors
docker exec postgres psql -U admin -d postgres -c "SELECT customer_id, full_name, customer_segment, domicile_currency FROM investors ORDER BY customer_id;"

# Count generated data
docker exec postgres psql -U admin -d postgres -c "SELECT 'investors' AS tbl, count(*) FROM investors UNION ALL SELECT 'personal_details', count(*) FROM personal_details UNION ALL SELECT 'risk_profiles', count(*) FROM risk_profiles UNION ALL SELECT 'investments', count(*) FROM investments UNION ALL SELECT 'liabilities', count(*) FROM liabilities UNION ALL SELECT 'currency_rates', count(*) FROM currency_rates;"

# Check currency rates
docker exec postgres psql -U admin -d postgres -c "SELECT * FROM currency_rates ORDER BY target_currency;"

# Active temporal records
docker exec postgres psql -U admin -d postgres -c "SELECT risk_profile, count(*) FROM risk_profiles WHERE is_active = true GROUP BY risk_profile;"
```

### SQS / S3 Debugging
```bash
# List SQS messages
aws --endpoint-url http://localhost:4566 sqs receive-message --queue-url http://localhost:4566/000000000000/investor-processing --max-number-of-messages 10

# Check DLQ
aws --endpoint-url http://localhost:4566 sqs receive-message --queue-url http://localhost:4566/000000000000/investor-processing-dlq --max-number-of-messages 10

# Check S3 output files
aws --endpoint-url http://localhost:4566 s3 ls s3://investor-output/data/
```

### Glue Job Debugging
```bash
# Check glue-runner container logs
docker logs glue-runner

# Re-run with different segment
docker exec -e SEGMENT=Premium glue-runner bash /opt/glue-runner.sh

# Check Lambda update
aws --endpoint-url http://localhost:4566 lambda update-function-code --function-name currency-refresh --zip-file fileb://lambda-currency-refresh/currency-refresh.zip
```

## Customization

### Investor Count
Edit `docker-compose.yml` seed-generator command:
```yaml
seed-generator:
  command: ["-count=500", "-family-pct=0.30", "-workers=8", "-dsn=postgres://admin:secret123@postgres:5432/postgres?sslmode=disable"]
```

### Glue Segment
Set `SEGMENT` env var before running:
```bash
docker exec -e SEGMENT=Corporate glue-runner bash /opt/glue-runner.sh
```
Or modify the default in `scripts/glue-runner.sh`.

### Currency Refresh Batch Sizes
```bash
aws --endpoint-url http://localhost:4566 lambda update-function-configuration \
  --function-name currency-refresh \
  --environment Variables="{VALUES_PER_STMT=50,STMTS_PER_BATCH=20,DB_DSN=postgres://admin:secret123@postgres:5432/postgres?sslmode=disable}"
```

## Deployment to AWS

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

OpenTofu creates: IAM roles/policies, S3 buckets, Glue Job, Lambda functions, S3→Lambda notifications, SQS queue + event mapping, SSM parameters, CloudWatch log groups.

## Notes

- **Floci storage mode** is `memory` — data lost on restart. Switch to `hybrid` in docker-compose.yml for persistence.
- **Glue uses `coalesce(1)`** — produces single CSV file per run.
- **Segment API is called once per segment**, not per row — department-level granularity as designed.
- **Generated investors (IDs 6+)** hit WireMock's catch-all stub (`exists:false`) and are routed to DLQ — expected local behavior since only seed investors are stubbed.
- **Java Lambdas** run via Floci's Docker executor using `public.ecr.aws/lambda/java:17`.
- **Go Lambda** uses `provided.al2023` with a static `bootstrap` binary compiled with `CGO_ENABLED=0` for Alpine/UPX compatibility.
