# investor-file-handler Lambda
**Language**: Java 11  

**Runtime**: `java11`  
**Handler**: `com.example.S3ToSqsLambda::handleRequest`  
**Source**: `lambda-investor-file-handler/`

---

## Business Context

The Glue job produces a single pipe-delimited CSV per segment run. This Lambda bridges the batch-oriented S3 output to message-oriented SQS processing. It parses each CSV row into a JSON message and publishes it to the `investor-processing` queue so downstream consumers can process investors individually.

## Input

**Trigger**: S3 `ObjectCreated:*` on `investor-output/` bucket, filtered to `*.csv` suffix.

**Event shape** (`S3Event`):

```json
{
  "Records": [{
    "s3": {
      "bucket": { "name": "investor-output" },
      "object": { "key": "data/part-00000-xxx.csv" }
    }
  }]
}
```

**CSV schema** (pipe-delimited, one header row):

```
customer_id|full_name|annual_income_usd|customer_segment|domicile_currency|join_date|segment_status
```

| Column | Type | Example |
|---|---|---|
| `customer_id` | int | `1` |
| `full_name` | string | `Alice Johnson` |
| `annual_income_usd` | decimal | `120000.00` |
| `customer_segment` | string | `Wealth` |
| `domicile_currency` | string | `USD` |
| `join_date` | date | `2020-03-15` |
| `segment_status` | json | `{"exists":true,"segment":"Wealth"}` |

## Processing

1. Read SSM parameters `/investor/sqs/queue-url` and `/investor/api/endpoint` at constructor time (failures logged, returns null).
2. For each S3 record in the event:
   - Download the CSV object from S3
   - Skip header row
   - Split each line on `|` — skip malformed rows (<7 fields)
   - Build `InvestorRecord` JSON and `SendMessage` to SQS
3. Return summary string with total message count.

## Output

**Direct**: Returns `"Published N messages to SQS"` string.

**Side-effect**: N JSON messages in `investor-processing` SQS queue:

```json
{
  "customer_id": 1,
  "full_name": "Alice Johnson",
  "annual_income_usd": 120000.0,
  "customer_segment": "Wealth",
  "domicile_currency": "USD",
  "join_date": "2020-03-15",
  "segment_status": "{\"exists\":true,\"segment\":\"Wealth\",\"status\":\"active\"}"
}
```

## Key Details

- Each S3 event triggers **one invocation** — the Lambda processes all records and publishes all messages before returning.
- No batching or deduplication on SQS publish.
- S3 bucket name and key are taken directly from the event; the `main()` method polls S3 by prefix for local dev.
- SSM fallback on failure: `null` — the Lambda continues but logs errors.
- Floci launches one Docker container per invocation using `public.ecr.aws/lambda/java:17`.
