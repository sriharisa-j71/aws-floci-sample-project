# Glue Job — InvestorToS3

**Language**: Scala 2.12.18  
**Framework**: Spark 3.5.4, Hadoop 3.3.5  
**Build**: SBT assembly  
**Main class**: `com.example.Main`  
**Source**: `glue-job/`

---

## Business Context

The Glue job is the core ETL step. It reads investors from PostgreSQL by segment, enriches each row with a segment-verification API call, and writes a single pipe-delimited CSV to S3. This CSV triggers the downstream Lambda/SQS validation pipeline.

## Input

**Parameters** (positional args):

| # | Name | Example |
|---|---|---|
| 1 | JDBC URL | `jdbc:postgresql://postgres:5432/postgres` |
| 2 | JDBC user | `admin` |
| 3 | JDBC password | `secret123` |
| 4 | S3 output path | `s3a://investor-output/data/` |
| 5 | Segment filter | `Wealth`, `Premium`, `Retail`, `Corporate` |
| 6 | API endpoint (optional) | `http://wiremock:8080` |

**SQL query** (built from segment):

```sql
SELECT customer_id, full_name, annual_income_usd, customer_segment,
       domicile_currency, join_date
FROM public.investors
WHERE customer_segment = '<segment>'
ORDER BY customer_id
```

**API call**: `GET /segment/{segment}` — called once per segment (not per row), returns:

```json
{"exists": true, "segment": "Wealth", "status": "active"}
```

The API endpoint is resolved from SSM parameter `/investor/api/endpoint` if not provided as a CLI arg.

## Processing

1. Create SparkSession with S3A Hadoop configuration for Floci/MinIO compatibility
2. Read investors via JDBC with the segment-filtered query
3. Call segment verification API (SSM or CLI arg)
4. Add `segment_status` column with the API response as a JSON string
5. Coalesce to 1 partition (`coalesce(1)`) — produces a single CSV file
6. Write pipe-delimited CSV with header to S3 output path (mode: `overwrite`)

**S3A config** (for Floci/MinIO):

```text
fs.s3a.endpoint=http://floci:4566
fs.s3a.path.style.access=true
fs.s3a.connection.ssl.enabled=false
fs.s3a.access.key=test
fs.s3a.secret.key=test
```

## Output

Single CSV file at `s3a://investor-output/data/part-00000-<uuid>.csv`:

```
customer_id|full_name|annual_income_usd|customer_segment|domicile_currency|join_date|segment_status
1|Alice Johnson|120000.00|Wealth|USD|2020-03-15|"{\"exists\":true,\"segment\":\"Wealth\",\"status\":\"active\"}"
```

Also writes `_SUCCESS` marker file.

## Key Details

- **One segment per run** — supported: `Wealth`, `Premium`, `Retail`, `Corporate`.
- **API called once per segment**, not per row — department-level granularity.
- **coalesce(1)** — single output file; remove for distributed multi-part output.
- **SBT assembly** produces fat JAR (`glue4-spark-job-assembly-1.0.jar`, ~40 MB).
- **Logging** via SLF4J + Logback (Logstash JSON format when configured).
- Runs in `glue-runner` Docker container (`glue-scala-minimal:latest`).
- The `SEGMENT` env var controls which segment is processed (default: `Wealth`).
