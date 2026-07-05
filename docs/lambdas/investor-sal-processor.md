# investor-sal-processor Lambda

**Language**: Java 17  
**Runtime**: `java17`  
**Handler**: `com.example.SqsProcessorLambda::handleRequest`  
**Source**: `lambda-investor-sal-processor/`

---

## Business Context

Validates each investor against the external CRM mock (WireMock), queries the daily risk score from PostgreSQL, and persists the risk assessment back to the mock API. Investors that don't exist in the CRM are flagged as batch failures and eventually routed to the DLQ after 3 retries.

## Input

**Trigger**: SQS event from `investor-processing` queue.

**Event shape** (`SQSEvent`):

```json
{
  "Records": [{
    "messageId": "abc123",
    "receiptHandle": "...",
    "body": "{\"customer_id\":1,\"full_name\":\"Alice Johnson\",\"annual_income_usd\":120000.0,\"customer_segment\":\"Wealth\",\"domicile_currency\":\"USD\",\"join_date\":\"2020-03-15\",\"segment_status\":\"...\"}"
  }]
}
```

## Processing

For each message in the batch:

1. **Deserialize** — Parse `body` as `InvestorRecord` JSON.
2. **Check existence** — `GET /investor/{customer_id}` against the API endpoint (from SSM `/investor/api/endpoint`):
   - Response contains `"exists": true` → proceed
   - Otherwise → add `BatchItemFailure` (message goes to DLQ after `maxReceiveCount=3`)
3. **Query risk score** — JDBC query against `daily_risk_scores`:
   ```sql
   SELECT risk_profile, composite_score, risk_category, calculation_date::text
   FROM daily_risk_scores
   WHERE customer_id = ? AND calculation_date = CURRENT_DATE
   ORDER BY calculation_date DESC LIMIT 1
   ```
4. **Save risk assessment** — `POST /risk-score/{customer_id}` to the API endpoint:

```json
{
  "customer_id": 1,
  "risk_profile": "Moderate",
  "composite_score": 55.42,
  "risk_category": "High",
  "calculation_date": "2026-07-05"
}
```

## Output

**Direct**: Returns `SQSBatchResponse` — a list of `BatchItemFailure` message IDs for investors that failed existence check or processing error.

**Side-effects**:
- Successful investors → `POST /risk-score/{id}` to WireMock
- Failed investors → message remains in queue for retry (`maxReceiveCount=3`) → DLQ

## Key Details

- **ReportBatchItemFailures** enabled: failed message IDs are returned so SQS can retry or DLQ them without deleting successful ones.
- DB connection established in constructor (`DriverManager.getConnection`). If it fails, `dbConn` is null and risk score queries are skipped.
- Default DB DSN: `jdbc:postgresql://postgres:5432/postgres?user=admin&password=secret123` (configurable via `DB_DSN` env var).
- `checkInvestorExists` uses response body text matching — looks for `"exists": true` substring (handles both with/without space).
- Timeout: 5s connect + 5s read for both API calls.
