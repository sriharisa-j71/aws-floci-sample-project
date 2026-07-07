# Testing Guide

## Prerequisites

- Docker & Docker Compose
- Java JDK 11 (Corretto or OpenJDK)
- Maven (for Java Lambda builds)
- SBT (for Glue JAR assembly)
- Go 1.21+ (for Go Lambda builds)

---

## Build All Artifacts

```bash
# 1. Glue job — Spark/Scala fat JAR
cd glue-job && sbt assembly && cd ..

# 2. Java Lambda JARs — file-handler + sal-processor
bash scripts/build-lambdas.sh

# 3. Go Lambda — currency-refresh
bash lambda-currency-refresh/build.sh

# 4. Go Lambda — risk-score-calculator
bash lambda-risk-score-calculator/build.sh

# 5. Glue runner Docker image (one-time)
docker build -t glue-scala-minimal:latest .
```

---

## Start Infrastructure

```bash
# Start all services
docker compose up -d

# Wait for healthy (check periodically)
docker ps --format "table {{.Names}}\t{{.Status}}"

# Expected containers: postgres (healthy), floci (healthy),
# wiremock (healthy), glue-runner (up), infra-setup (completed)
```

On first startup, `infra-setup` creates:
- SSM parameters (API endpoint, SQS queue URL)
- SQS queues (investor-processing + DLQ)
- S3 buckets (glue-artifacts, investor-output, currency-rates-input)
- Lambda functions (file-handler, sal-processor, currency-refresh, risk-score-calculator)
- S3 → Lambda notifications
- SQS → Lambda event source mapping

---

## Component Tests

### 1. Database & Seed Data

Verify PostgreSQL has expected tables and data:

```bash
# Table count
docker exec postgres psql -U admin -d postgres \
  -c "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"

# Row counts
docker exec postgres psql -U admin -d postgres \
  -c "SELECT 'investors' AS tbl, count(*) FROM investors
      UNION ALL SELECT 'personal_details', count(*) FROM personal_details
      UNION ALL SELECT 'risk_profiles', count(*) FROM risk_profiles
      UNION ALL SELECT 'investments', count(*) FROM investments
      UNION ALL SELECT 'liabilities', count(*) FROM liabilities
      UNION ALL SELECT 'currency_rates', count(*) FROM currency_rates
      UNION ALL SELECT 'daily_risk_scores', count(*) FROM daily_risk_scores"
```

### 2. Currency Refresh Lambda

Upload a rates file to trigger the Go Lambda:

```bash
# Generate a test file
./tools/generate-currency-file/generate-currency-file -out /tmp/rates.csv

# Upload to S3 (triggers currency-refresh Lambda)
aws --endpoint-url http://localhost:4566 s3 cp /tmp/rates.csv s3://currency-rates-input/

# Wait and verify
sleep 5
docker exec postgres psql -U admin -d postgres -c "SELECT * FROM currency_rates ORDER BY target_currency"

# Expected: N rows with the uploaded rates, not the seed data
```

**What to check:**
- Lambda return code 200
- `currency_rates` table has the new rows
- Seed rates are gone (table was truncated)

### 3. Risk-Score-Calculator Lambda

The same S3 upload that triggers currency-refresh also triggers risk-score-calculator:

```bash
# Verify scores were calculated
docker exec postgres psql -U admin -d postgres \
  -c "SELECT customer_id, composite_score, risk_category, risk_profile,
             investment_count, debt_to_income_ratio
      FROM daily_risk_scores
      WHERE calculation_date = CURRENT_DATE
      ORDER BY customer_id LIMIT 10"
```

Expected: one row per investor with `composite_score` (0-100) and `risk_category`.

**To trigger manually** (if auto-trigger didn't fire):

```bash
aws --endpoint-url http://localhost:4566 lambda invoke \
  --function-name risk-score-calculator \
  --cli-binary-format raw-in-base64-out \
  --payload '{"Records":[{"s3":{"bucket":{"name":"currency-rates-input"},"object":{"key":"currency_rates.csv"}}}]}' \
  /tmp/response.json
```

### 4. Glue Job (ETL → S3)

Run the Spark job for a specific segment:

```bash
# Wealth segment (default)
docker exec glue-runner bash /opt/glue-runner.sh

# Or with specific segment
docker exec -e SEGMENT=Premium glue-runner bash /opt/glue-runner.sh

# Check S3 output
aws --endpoint-url http://localhost:4566 s3 ls s3://investor-output/data/
aws --endpoint-url http://localhost:4566 s3 cp s3://investor-output/data/part-*.csv -
```

**Expected**: single CSV file with pipe-delimited rows for that segment.

### 5. File-Handler + SQS Pipeline

S3 upload of a CSV triggers the file-handler Lambda, which publishes SQS messages:

```bash
# Check if messages were published
aws --endpoint-url http://localhost:4566 sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/investor-processing \
  --attribute-names ApproximateNumberOfMessages
```

### 6. SAL Processor + WireMock

SQS triggers the sal-processor Lambda, which:
1. Checks investor existence via `GET /investor/{id}` (WireMock)
2. Queries `daily_risk_scores` from PostgreSQL
3. POSTs risk assessment to `POST /risk-score/{id}` (WireMock)

```bash
# Check WireMock request log
curl -s http://localhost:8080/__admin/requests | python3 -c "
import json,sys
data = json.load(sys.stdin)
risk_posts = [r for r in data['requests'] if '/risk-score/' in r['request']['url']]
investor_gets = [r for r in data['requests'] if '/investor/' in r['request']['url'] and '/segment/' not in r['request']['url']]
print(f'Investor GETs: {len(investor_gets)}')
print(f'Risk-score POSTs: {len(risk_posts)}')
for r in risk_posts[:5]:
    print(f'  POST {r[\"request\"][\"url\"]}: {r[\"request\"][\"body\"]}')
"
```

**Expected**: 
- Each investor GET returns `{"exists": true, ...}`
- Each risk-score POST returns `{"status": "saved", ...}`
- Number of POSTs equals number of investors in the segment CSV

---

## End-to-End Flow Test

Run the full pipeline in sequence:

```bash
# 1. Verify DB is seeded
docker exec postgres psql -U admin -d postgres -c "SELECT count(*) FROM investors"

# 2. Upload currency rates (triggers currency-refresh + risk-score-calculator)
./tools/generate-currency-file/generate-currency-file -out /tmp/rates.csv
aws --endpoint-url http://localhost:4566 s3 cp /tmp/rates.csv s3://currency-rates-input/
sleep 10

# 3. Verify risk scores
docker exec postgres psql -U admin -d postgres \
  -c "SELECT count(*) FROM daily_risk_scores WHERE calculation_date = CURRENT_DATE"

# 4. Run Glue job for each segment
for seg in Wealth Premium Retail Corporate; do
  docker exec -e SEGMENT=$seg glue-runner bash /opt/glue-runner.sh
done

# 5. Wait for Lambda processing
sleep 30

# 6. Verify WireMock received risk assessments
curl -s http://localhost:8080/__admin/requests | python3 -c "
import json,sys
d = json.load(sys.stdin)
posts = [r for r in d['requests'] if r['request']['method'] == 'POST']
print(f'Total POST requests: {len(posts)}')
"

# 7. Sample a risk assessment
curl -s http://localhost:8080/__admin/requests | python3 -c "
import json,sys
d = json.load(sys.stdin)
posts = [r for r in d['requests'] if r['request']['method'] == 'POST']
if posts:
    r = posts[0]
    print(json.dumps(json.loads(r['request']['body']), indent=2))
"
```

---

## Debugging

### Lambda not triggering?

```bash
# Check S3 notification configuration
aws --endpoint-url http://localhost:4566 s3api get-bucket-notification-configuration --bucket investor-output

# Check SQS→Lambda event source mapping
aws --endpoint-url http://localhost:4566 lambda list-event-source-mappings --function-name investor-sal-processor

# Check Lambda exists
aws --endpoint-url http://localhost:4566 lambda get-function --function-name investor-file-handler
```

### SQS messages stuck?

```bash
# Check queue stats
aws --endpoint-url http://localhost:4566 sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/investor-processing \
  --attribute-names All

# Check DLQ
aws --endpoint-url http://localhost:4566 sqs receive-message \
  --queue-url http://localhost:4566/000000000000/investor-processing-dlq \
  --max-number-of-messages 10
```

### Lambda errors?

```bash
# Floci logs (all Lambda invocations)
docker logs floci 2>&1 | grep -i error

# File-handler
docker logs floci 2>&1 | grep "investor-file-handler"

# SAL processor
docker logs floci 2>&1 | grep "investor-sal-processor"

# Currency refresh
docker logs floci 2>&1 | grep "currency-refresh"

# Risk score calculator
docker logs floci 2>&1 | grep "risk-score-calculator"

# S3 notifications
docker logs floci 2>&1 | grep "notification"
```

### Risk score missing?

```bash
# Check the SQL query the Lambda runs
docker exec postgres psql -U admin -d postgres \
  -c "SELECT i.customer_id, i.annual_income_usd,
             COALESCE(rp.risk_profile, 'Moderate'), rp.risk_score
      FROM investors i
      LEFT JOIN risk_profiles rp ON rp.customer_id = i.customer_id AND rp.is_active
      ORDER BY i.customer_id LIMIT 5"

# Verify daily_risk_scores table has data
docker exec postgres psql -U admin -d postgres \
  -c "SELECT * FROM daily_risk_scores WHERE calculation_date = CURRENT_DATE LIMIT 3"
```

### WireMock not receiving requests?

```bash
# Check WireMock is healthy
curl -s http://localhost:8080/__admin/health

# Check the mapping was loaded
curl -s http://localhost:8080/__admin/mappings | python3 -m json.tool | head -40

# Restart WireMock after mapping file changes
docker restart wiremock
```

### Infrastructure needs re-setup?

```bash
# Re-run infra-setup (non-destructive)
docker compose up -d infra-setup
```

---

## Resetting State

```bash
# Truncate daily_risk_scores (forces recalculation)
docker exec postgres psql -U admin -d postgres -c "TRUNCATE daily_risk_scores"

# Clear SQS queues
aws --endpoint-url http://localhost:4566 sqs purge-queue \
  --queue-url http://localhost:4566/000000000000/investor-processing
aws --endpoint-url http://localhost:4566 sqs purge-queue \
  --queue-url http://localhost:4566/000000000000/investor-processing-dlq

# Clear S3 output
aws --endpoint-url http://localhost:4566 s3 rm s3://investor-output/data/ --recursive

# Clear WireMock request log (restart clears in-memory state)
docker restart wiremock
```
