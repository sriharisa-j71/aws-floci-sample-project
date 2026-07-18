# lambda-sql-query-runner

Go Lambda that executes parameterized SQL queries against PostgreSQL, uploads results to S3, and returns metadata via a Function URL. Deployed to Floci/AWS via OpenTofu (`infra/main.tf`).

## Runtime

- `provided.al2023` custom runtime (`bootstrap` binary)
- 256 MB memory, 30s timeout
- Function URL with `auth-type = NONE`

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `DB_DSN` | PostgreSQL connection string | `postgres://admin:secret123@postgres:5432/postgres?sslmode=disable` |
| `S3_BUCKET` | S3 bucket for query results | `query-results` |
| `S3_PREFIX` | S3 key prefix for uploaded results | `query-results` |
| `AWS_ENDPOINT_URL` | Floci/LocalStack endpoint (path-style S3) | (set by tofu) |
| `QUERIES_TOML_PATH` | Path to TOML query definitions file | `queries.toml` |

---

## Query Sources

Queries are resolved from two sources. Resolution order (default `auto` mode):

1. **Code registry** — built-in queries compiled into the binary (`queries.go`)
2. **TOML file** — `queries.toml` bundled in the deployment zip alongside the binary

Override resolution with the `X-Query-Source` header.

### Built-in Queries

| Name | Base SQL | Positional Params | Dynamic Where Columns |
|---|---|---|---|
| `investors_by_segment` | `SELECT * FROM investors WHERE customer_segment = $1` | `segment` | — |
| `risk_report` | `SELECT i.customer_id, i.full_name, i.customer_segment, drs.composite_score, drs.risk_category, drs.calculation_date FROM investors i JOIN daily_risk_scores drs ON i.customer_id = drs.customer_id WHERE 1=1` | — | `customer_id` → `i.customer_id`, `risk_category` → `drs.risk_category`, `customer_segment` → `i.customer_segment` |
| `all_investors` | `SELECT * FROM investors WHERE 1=1` | — | `customer_id`, `customer_segment`, `domicile_currency` |
| `investments_full` | `SELECT inv.customer_id, inv.type, inv.ticker, inv.description, inv.quantity, inv.current_value_usd, inv.effective_start_date FROM investments inv WHERE 1=1` | — | `customer_id`, `type`, `ticker` |
| `liabilities_full` | `SELECT li.customer_id, li.type, li.creditor, li.outstanding_amount_usd, li.interest_rate, li.monthly_payment_usd, li.effective_start_date FROM liabilities li WHERE 1=1` | — | `customer_id`, `type`, `creditor` |

### Query Resolution Modes

| `X-Query-Source` | Behavior |
|---|---|
| `auto` (default) or omitted | Checks code registry first, then falls back to TOML file |
| `code` | Only checks code registry; returns 404 if not found |
| `toml` | Only checks `queries.toml` at `QUERIES_TOML_PATH`; returns 404 if not found |

---

## API

### HTTP Method

`GET` or `POST` — both work. A body is only required for parameterized or filtered queries.

### Request Headers

| Header | Required | Description |
|---|---|---|
| `X-Query-Name` | **Yes** | Name of the query to execute (must match a name in code registry or TOML) |
| `X-Query-Source` | No | `auto` (default), `code`, or `toml` — controls where the query is looked up |
| `Content-Type` | No | `application/json` if sending a body |

### Request Body (JSON, all fields optional)

```json
{
  "params": ["Wealth"],
  "where": {
    "risk_category": "High",
    "customer_segment": ["Wealth", "Premium"]
  },
  "where_list": [
    {"conjunction": "AND", "condition": "annual_income_usd > 200000"}
  ],
  "limit": 100,
  "offset": 0
}
```

#### `params` — Positional Parameters

Array of values substituted as `$1`, `$2`, ... in the query's base SQL. Order matters.

Used by queries with `params` defined (e.g. `investors_by_segment` which has `params = ["segment"]`).

```
"params": ["Wealth"]
→ WHERE customer_segment = $1  →  WHERE customer_segment = 'Wealth'
```

#### `where` — Dynamic Equality / IN Filters

Object of `{ column: value }` pairs. Only columns listed in the query's `filters` array are allowed — unknown columns are silently ignored.

- **Scalar value**: generates `AND <column> = $N`
- **Array value**: generates `AND <column> IN ($N, $N+1, ...)`

Column names can be remapped via `filter_map` (e.g. `customer_id` → `i.customer_id` for JOINed queries). If no `filter_map` exists and `filter_prefix` is set, the prefix is prepended.

```
"where": {"risk_category": "High", "customer_segment": ["Wealth", "Premium"]}
→ AND drs.risk_category = $2 AND i.customer_segment IN ($3, $4)
```

#### `where_list` — Raw SQL Conditions

Array of `{ conjunction, condition }` objects appended as raw SQL. Use for conditions not covered by `where` (aggregations, comparisons, LIKE, etc.).

- `conjunction`: `AND` (default), `OR`, `AND NOT`, etc. — uppercased automatically
- `condition`: raw SQL fragment (no parameterization — values must be inlined safely)

```
"where_list": [{"conjunction": "AND", "condition": "annual_income_usd > 200000"}]
→ AND annual_income_usd > 200000
```

#### `limit` / `offset` — Pagination

- `limit`: integer — appended as `LIMIT N` if the query has `limit = true` in its definition
- `offset`: integer — appended as `OFFSET N`

### Response — Success

HTTP 200:

```json
{
  "status": "success",
  "query": "all_investors",
  "row_count": 55,
  "s3_key": "query-results/2026-07-18/all_investors_1784394599301.txt",
  "s3_bucket": "query-results",
  "execution_ms": 7
}
```

| Field | Type | Description |
|---|---|---|
| `status` | string | `"success"` |
| `query` | string | Name of the executed query |
| `row_count` | int | Number of rows returned |
| `s3_key` | string | S3 object key of the uploaded result |
| `s3_bucket` | string | S3 bucket where result was uploaded |
| `execution_ms` | int | End-to-end execution time in milliseconds |

### Response — Error

HTTP 400/404/500:

```json
{
  "status": "error",
  "query": "unknown_query",
  "error": "query \"unknown_query\" not found in any source",
  "sql": "SELECT ..."
}
```

| Field | Type | Description |
|---|---|---|
| `status` | string | `"error"` |
| `query` | string | Name of the query that was requested |
| `error` | string | Human-readable error message |
| `sql` | string | The generated SQL (present for query execution errors, absent for resolution errors) |

---

## S3 Output

Every successful query uploads results to S3.

### Path Format

```
s3://<S3_BUCKET>/<S3_PREFIX>/<YYYY-MM-DD>/<query_name>_<unix_millis>.txt
```

Example: `s3://query-results/query-results/2026-07-18/all_investors_1784394599301.txt`

Per-query `s3_bucket` and `s3_prefix` in the query definition override the environment defaults. If not set, falls back to `S3_BUCKET`/`S3_PREFIX` env vars.

### Output Format

Pipe-delimited text with header row:

```
customer_id|full_name|customer_segment|annual_income_usd|domicile_currency
1|Alice Johnson|Wealth|450000|USD
2|Bob Smith|Premium|120000|USD
3|Carol Davis|Wealth|380000|CHF
```

---

## Usage Examples

```bash
# Get function URL from tofu output
FUNCTION_URL=$(cd infra && tofu output -raw sql_query_runner_function_url)

# --- Simple queries (GET, no body) ---

# All investors (no filters)
curl "$FUNCTION_URL" -H "X-Query-Name: all_investors"

# Risk report
curl "$FUNCTION_URL" -H "X-Query-Name: risk_report"

# --- Parameterized query ---

# Investors by segment (positional $1 param)
curl -X POST "$FUNCTION_URL" \
  -H "X-Query-Name: investors_by_segment" \
  -d '{"params":["Wealth"]}'

# --- Dynamic filters ---

# Risk report filtered to High-risk investors only
curl -X POST "$FUNCTION_URL" \
  -H "X-Query-Name: risk_report" \
  -d '{"where":{"risk_category":"High"}}'

# Investments filtered by type (array → IN clause)
curl -X POST "$FUNCTION_URL" \
  -H "X-Query-Name: investments_full" \
  -d '{"where":{"type":["Stock","Bond"]},"limit":20}'

# --- Raw SQL conditions ---

# High-income investors via where_list
curl -X POST "$FUNCTION_URL" \
  -H "X-Query-Name: all_investors" \
  -d '{"where_list":[{"conjunction":"AND","condition":"annual_income_usd > 200000"}],"limit":5}'

# --- Pagination ---

# Page 2 of 10 results
curl -X POST "$FUNCTION_URL" \
  -H "X-Query-Name: all_investors" \
  -d '{"limit":10,"offset":10}'

# --- Force query source ---

# Only look in TOML (skip code registry)
curl "$FUNCTION_URL" \
  -H "X-Query-Name: all_investors" \
  -H "X-Query-Source: toml"
```

### Verify S3 Output

```bash
# List results
aws --endpoint-url http://localhost:4566 s3 ls s3://query-results/query-results/ --recursive

# Download and view
aws --endpoint-url http://localhost:4566 s3 cp s3://query-results/query-results/2026-07-18/all_investors_1784394599301.txt - | head -5
```

---

## Adding New Queries

### Via TOML (no rebuild)

Edit `queries.toml` and re-package the zip:

```toml
[[queries]]
name = "high_value_investors"
sql = """
SELECT i.customer_id, i.full_name, i.annual_income_usd,
       i.customer_segment, i.domicile_currency
FROM investors i
WHERE 1=1
"""
dynamic_where = true
filters = ["customer_id", "customer_segment", "domicile_currency"]
filter_prefix = "i."
limit = true
s3_bucket = "query-results"
s3_prefix = "query-results/high-value"
```

TOML query definition fields:

| Field | Type | Description |
|---|---|---|
| `name` | string | Query name (used in `X-Query-Name` header) |
| `sql` | string | Base SQL with `$1`, `$2`... placeholders |
| `params` | string[] | Named positional params (documentation only; values from `params` body field) |
| `dynamic_where` | bool | Enable `where` body field for this query |
| `filters` | string[] | Allowed columns for `where` — unknown columns silently ignored |
| `filter_prefix` | string | Prefix prepended to `where` column names (e.g. `i.` for aliased tables) |
| `filter_map` | {string: string} | Explicit column → qualified name mapping (overrides `filter_prefix`) |
| `limit` | bool | Enable `LIMIT` from body |
| `s3_bucket` | string | Override S3 bucket (default: `S3_BUCKET` env var) |
| `s3_prefix` | string | Override S3 prefix (default: `S3_PREFIX` env var) |

Then rebuild and deploy:

```bash
bash lambda-sql-query-runner/build.sh
cd infra && tofu apply
```

### Via Code (requires rebuild)

Add a new entry to `codeRegistry` in `queries.go`:

```go
"my_query": {
    Name:         "my_query",
    SQL:          "SELECT * FROM my_table WHERE status = $1",
    Params:       []string{"status"},
    DynamicWhere: false,
    Limit:        true,
    S3Bucket:     defBucket,
    S3Prefix:     defPrefix,
},
```

---

## Build

```bash
cd lambda-sql-query-runner
bash build.sh
# Outputs:
#   bootstrap           — ELF binary (statically linked, UPX-compressed)
#   sql-query-runner.zip — deployment zip (bootstrap + queries.toml)
```

Build: `CGO_ENABLED=0`, `GOOS=linux`, `GOARCH=amd64`.

---

## Dependencies

| Module | Purpose |
|---|---|
| `github.com/aws/aws-lambda-go` | Lambda runtime + API Gateway V2 event types |
| `github.com/aws/aws-sdk-go-v2` + `/config` + `/service/s3` | S3 client (path-style, endpoint-aware) |
| `github.com/jackc/pgx/v5` | PostgreSQL connection pool (`pgxpool`) |
| `github.com/BurntSushi/toml` | TOML query definitions parser |

---

## Infrastructure (OpenTofu)

Managed in `infra/main.tf`:

| Resource | Purpose |
|---|---|
| `aws_s3_bucket.query_results` | S3 bucket for query output |
| `aws_s3_object.lambda_sql_query_runner_zip` | Deployment zip uploaded to `glue-artifacts` |
| `aws_iam_role.lambda_sql_query_runner` | Lambda execution role |
| `aws_iam_role_policy.lambda_sql_query_runner` | S3 write + CloudWatch logs policy |
| `aws_lambda_function.sql_query_runner` | Lambda function (`provided.al2023`) |
| `aws_lambda_function_url.sql_query_runner` | Public Function URL (`auth-type = NONE`) |

Outputs: `sql_query_runner_function_url`, `sql_query_runner_function_name`.
