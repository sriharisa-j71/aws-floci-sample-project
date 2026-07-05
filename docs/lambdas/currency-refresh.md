# currency-refresh Lambda

**Language**: Go 1.21+  
**Runtime**: `provided.al2023`  
**Handler**: `bootstrap`  
**Source**: `lambda-currency-refresh/`

---

## Business Context

Currency rates change daily. This Lambda reloads the `currency_rates` table from a freshly uploaded rates file, enabling downstream pipelines (risk-score-calculator) to use up-to-date conversion rates for cross-currency normalization.

## Input

**Trigger**: S3 `ObjectCreated:*` on `currency-rates-input/` bucket (any key, no suffix filter).

**Event shape** (`S3Event`):

```json
{
  "Records": [{
    "s3": {
      "bucket": { "name": "currency-rates-input" },
      "object": { "key": "currency_rates.csv" }
    }
  }]
}
```

**File format** (pipe-delimited CSV with header):

```
base_currency|target_currency|rate|effective_date
USD|USD|1.000000|2026-07-05
USD|GBP|0.790000|2026-07-05
...
```

| Column | Type | Example |
|---|---|---|
| `base_currency` | string (3-letter) | `USD` |
| `target_currency` | string (3-letter) | `GBP` |
| `rate` | decimal (6+ places) | `0.790000` |
| `effective_date` | date | `2026-07-05` |

## Processing

1. Download file from S3
2. Validate header row: `base_currency|target_currency|rate|effective_date`
3. Parse each data row — validate rate is a float, date is `YYYY-MM-DD`
4. `TRUNCATE currency_rates`
5. Batch INSERT using configurable chunk sizes:
   - `VALUES_PER_STMT` — rows per INSERT statement (default 25)
   - `STMTS_PER_BATCH` — statements per `pgx.SendBatch` (default 10)
6. Log count of upserted rates

## Output

**Direct**: Returns `nil` on success, `error` on failure (S3 download, parse, or DB error).

**Side-effects**:
- `currency_rates` table is truncated and reloaded with new rates
- Old rates are permanently removed

## Key Details

- Static `pgxpool.Pool` — connection stays alive across warm invocations.
- S3 client configured with `UsePathStyle=true` and `BaseEndpoint` from `AWS_ENDPOINT_URL` env var (required for Floci/MinIO compat).
- Binary compiled with `CGO_ENABLED=0` for static linking, optionally UPX-compressed (~13 MB → ~5 MB).
- Deployment: raw `bootstrap` binary uploaded to S3, Lambda uses `provided.al2023` runtime.
- The S3 notification on `currency-rates-input` triggers **both** `currency-refresh` and `risk-score-calculator` in parallel.
