# risk-score-calculator Lambda

**Language**: Go 1.21+  
**Runtime**: `provided.al2023`  
**Handler**: `bootstrap`  
**Source**: `lambda-risk-score-calculator/`

---

## Business Context

Investment banks need a daily composite risk score for every customer to power portfolio analytics, margin requirements, and regulatory reporting. This Lambda computes a weighted score from three dimensions (risk profile, investment performance, liability burden) and stores the result in a time-partitioned table for daily point-in-time queries.

## Input

**Trigger**: S3 `ObjectCreated:*` on `currency-rates-input/` bucket (same trigger as currency-refresh Lambda — both fire in parallel).

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

The Lambda **ignores** the triggering S3 object — it uses the event only as a signal to recalculate all scores.

## Processing

### 1. Load currency rates

```sql
SELECT target_currency, rate FROM currency_rates WHERE base_currency = 'USD'
```

Builds a `map[target]rate` for normalizing cross-currency values (not yet applied to individual assets — used for future currency-aware scoring).

### 2. Load all investors

```sql
SELECT i.customer_id, i.annual_income_usd, i.domicile_currency,
       COALESCE(rp.risk_profile, 'Moderate'), rp.risk_score
FROM investors i
LEFT JOIN risk_profiles rp ON rp.customer_id = i.customer_id AND rp.is_active
ORDER BY i.customer_id
```

Handles investors without an active risk profile by defaulting to `Moderate`.

### 3. Score each investor

For every investor, three sub-scores are calculated:

| Sub-score | Weight | Formula |
|---|---|---|
| **Profile Score** | 30% | `risk_score` (1-100) if set, else default: Conservative=25, Moderate=50, Aggressive=75 |
| **Investment Risk** | 25% | `diversityScore × 0.5 + perfScore × 0.5` where diversity is inverse of distinct types/8 and perf is purchase/current value ratio mapped to 0-100 |
| **Liability Risk** | 45% | `dtiScore × 0.6 + countScore × 0.4` where dti = outstanding/income × 20 (capped at 100) and count = number of liability types × 25 (capped at 100) |

**Composite**: `profileScore × 0.30 + investmentScore × 0.25 + liabilityScore × 0.45`

**Category thresholds**:

| Range | Category |
|---|---|
| 0-20 | Very Low |
| 21-40 | Low |
| 41-55 | Moderate |
| 56-70 | High |
| 71-100 | Critical |

### 4. Upsert results

Single transaction batch upsert:

```sql
INSERT INTO daily_risk_scores (customer_id, calculation_date, risk_profile, ...)
VALUES ($1, CURRENT_DATE, $2, ...)
ON CONFLICT (customer_id, calculation_date) DO UPDATE SET ...
```

## Output

**Direct**: Returns `nil` on success, `error` on failure.

**Side-effects**: `daily_risk_scores` table has N rows inserted/updated for `CURRENT_DATE` — one per investor.

## Key Details

- Ignores the S3 event content — triggered by file upload but recalculates all scores from scratch each time.
- Uses `pgxpool` for connection pooling — pool created in `init()`.
- Single transaction for all upserts — any failure rolls back the entire batch.
- `debt_to_income_ratio` = total outstanding liabilities / annual income.
- Investors with no investments get `investmentScore = 50` (neutral).
- Investors with no liabilities get `liabilityScore = 0`.
- Deployment: zip containing `bootstrap` binary (not raw binary) — required for `provided.al2023`.
- Environment variables: `DB_DSN`, `AWS_ENDPOINT_URL`, `AWS_DEFAULT_REGION`.
