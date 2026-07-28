# Regression Testing Framework

CLI-based regression testing framework for Floci-deployed AWS pipelines. Defines test suites as JSON, executes actions against local or real AWS services (S3, Lambda, SQS, Glue, SSM, CloudWatch), and persists results to PostgreSQL with rich reporting.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Regression Framework (Spring Shell CLI)              │
│                                                                             │
│  ┌───────────┐  ┌──────────────┐  ┌───────────────┐  ┌──────────────────┐  │
│  │ Shell     │  │ TestSuite    │  │ TestCase      │  │ TestStep         │  │
│  │ Commands  │─▶│ Runner       │─▶│ Runner        │─▶│ Runner           │  │
│  └───────────┘  └──────┬───────┘  └───────┬───────┘  └───────┬──────────┘  │
│                         │                  │                  │              │
│                         ▼                  ▼                  ▼              │
│                  ┌──────────────┐  ┌───────────────┐  ┌──────────────────┐  │
│                  │ Lifecycle    │  │ Action        │  │ Verifier         │  │
│                  │ Engine       │─▶│ Registry      │─▶│ Registry         │  │
│                  │ (hooks)      │  │ (handlers)    │  │ (verifiers)      │  │
│                  └──────────────┘  └───────┬───────┘  └───────┬──────────┘  │
│                                            │                  │              │
│                                            ▼                  ▼              │
│                                     ┌──────────────────────────────────┐    │
│                                     │    Context Resolver              │    │
│                                     │  {{env.*}} {{config.*}}          │    │
│                                     │  {{resolved.*}} {{step.*}}       │    │
│                                     └──────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              ▼                       ▼                       ▼
     ┌────────────────┐    ┌──────────────────┐    ┌────────────────────┐
     │ PostgreSQL     │    │ AWS / Localstack │    │ Report Generator   │
     │ (test results) │    │ (S3, Lambda,     │    │ (text, JSON, HTML) │
     │                │    │  SQS, Glue, SSM, │    │                    │
     │                │    │  CloudWatch)     │    │                    │
     └────────────────┘    └──────────────────┘    └────────────────────┘
```

### Core Components

| Layer | Package | Responsibility |
|---|---|---|
| **Shell Commands** | `shell/` | Spring Shell CLI — `run`, `report`, `list-runs`, `history`, `inspect`, `compare`, `config`, `archive`, `recover` |
| **Runners** | `runner/` | Orchestrate execution: `TestSuiteRunner` → `TestCaseRunner` → `TestStepRunner` |
| **Context** | `context/` | Hierarchical context chain: `SuiteContext` → `TestCaseContext` → `TestStepContext` with shared state |
| **Actions** | `action/` | `ActionHandler` implementations — S3, Lambda, SQS, Glue, SSM, CloudWatch. Each maps `actionType` (e.g. `s3:putObject`) to AWS SDK calls |
| **Verifiers** | `verifier/` | `Verifier` implementations — `jsonPath`, `objectExists`, `messageCount`, `metricThreshold`, `logContains`, `jobStatus`. Assert against action responses |
| **Lifecycle** | `lifecycle/` | Executes `beforeAll`/`afterAll`/`beforeEach`/`afterEach` hooks with optional `stopOnFailure` |
| **Context Resolver** | `context/` | Template engine resolving `{{ }}` expressions: `env.*`, `config.*`, `shared.*`, `resolved.*` (from data rows), `step.<name>.response.*` |
| **Reporting** | `report/` | Output formats: console text, JSON, HTML (via JTE template) |
| **Persistence** | `db/` | Spring JDBC repositories storing results in PostgreSQL |
| **Config** | `config/` | Spring Boot configuration, AWS endpoint overrides, Flyway migrations |

### Data Model

```
test_run (1) ──▶ suite_result (1)
   │
   └──▶ test_case_result (N)
          │
          └──▶ test_step_result (M)
                 │
                 └──▶ verification_result (K)
```

- `test_run`: top-level run with suite metadata, timestamps, pass/fail counts
- `suite_result`: snapshot of suite JSON + lifecycle hook results
- `test_case_result`: per-case result with data row, step counts
- `test_step_result`: per-step result with action type/params, response, retry count, failure category (`ASSERTION`, `TIMEOUT`, `ACTION_ERROR`, `FLAKY`)
- `verification_result`: per-verification assertion with actual/expected values

## Tech Stack

- **Java 25** with Spring Boot 4.0.7
- **Spring Shell 3.4.0** — interactive CLI
- **PostgreSQL** — result persistence (via Flyway migrations)
- **AWS SDK v2 2.30.11** — S3, Lambda, SQS, Glue, SSM, CloudWatch
- **JTE 3.1.16** — HTML report templating
- **Jackson** — JSON parsing/serialization
- **GraalVM native-maven-plugin** — native image build support
- **Testcontainers** — integration tests

## Prerequisites

- Java 25 (JDK)
- Maven 3.9+
- Docker & Docker Compose (for PostgreSQL + localstack/Floci)
- GraalVM (optional, for native image)

## How to Run

### 1. Start Infrastructure

```bash
# PostgreSQL for test results
docker compose -f regression-framework/docker-compose.regression.yml up -d

# (Optional) Floci/localstack for AWS service endpoints
# docker compose -f docker-compose.yml up -d
```

### 2. Run via Maven (JAR mode)

```bash
cd regression-framework
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

This starts the Spring Shell interactive CLI:

```
shell:>help
shell:>list-suites
shell:>run --suite investor-pipeline
shell:>list-runs --limit 5
shell:>inspect --run-id <uuid>
shell:>report --run-id <uuid> --format html --output /tmp/report.html
```

### 3. Run a Suite Non-Interactively

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="run --suite investor-pipeline"
```

### 4. Build and Run as JAR

```bash
cd regression-framework
mvn clean package -DskipTests
java -jar target/regression-framework-1.0.0-SNAPSHOT.jar
```

### 5. Build Native Image (GraalVM)

```bash
cd regression-framework
mvn -Pnative native:compile -DskipTests
./target/regression-framework
```

Non-interactive native mode:

```bash
./target/regression-framework run --suite investor-pipeline
```

## Test Suites

Suites are JSON files (`.json`) in directories configured by `regression.suite-dirs` (default: `test-suites/`).

### Suite Schema

```json
{
  "suiteName": "investor-pipeline",
  "schemaVersion": 1,
  "tags": ["smoke", "regression"],
  "config": { "region": "us-east-1" },
  "beforeAll": [ { "hookType": "beforeAll", "actions": [...], "order": 0 } ],
  "afterAll":  [ { "hookType": "afterAll",  "actions": [...], "order": 0 } ],
  "cases": [
    {
      "caseName": "create-bucket",
      "dataSource": "investor-pipeline",
      "steps": [
        {
          "stepName": "upload-data",
          "retryCount": 2,
          "timeoutMs": 30000,
          "action": { "actionType": "s3:putObject", "params": { ... } },
          "verifications": [
            { "verifierType": "jsonPath", "expected": { "path": "$.statusCode", "value": 200 } }
          ]
        }
      ]
    }
  ]
}
```

### Supported Action Types

| Action Type | Handler | Parameters |
|---|---|---|
| `s3:putObject` | S3ActionHandler | bucket, key, content |
| `s3:getObject` | S3ActionHandler | bucket, key |
| `s3:deleteObject` | S3ActionHandler | bucket, key |
| `s3:listObjects` | S3ActionHandler | bucket, prefix |
| `lambda:invoke` | LambdaActionHandler | functionName, payload |
| `sqs:sendMessage` | SQSActionHandler | queueUrl, messageBody |
| `sqs:receiveMessage` | SQSActionHandler | queueUrl |
| `sqs:purgeQueue` | SQSActionHandler | queueUrl |
| `glue:startJobRun` | GlueActionHandler | jobName |
| `glue:getJobRun` | GlueActionHandler | jobName, jobRunId |
| `glue:listJobs` | GlueActionHandler | — |
| `ssm:getParameter` | SSMActionHandler | name |
| `ssm:putParameter` | SSMActionHandler | name, value, type |
| `cloudwatch:putMetricData` | CloudWatchActionHandler | namespace, metricName, value, unit |
| `cloudwatch:getMetricData` | CloudWatchActionHandler | namespace, metricName, period |
| `logs:describeLogGroups` | CloudWatchActionHandler | logGroupPrefix |
| `logs:filterLogEvents` | CloudWatchActionHandler | logGroupName, filterPattern |

### Supported Verifier Types

| Verifier Type | Verifier | Use |
|---|---|---|
| `jsonPath` | ResponseVerifier | Assert JSON path value in action response |
| `statusCode` | ResponseVerifier | Assert HTTP/response status code |
| `bodyContains` | ResponseVerifier | Assert substring in response body |
| `objectExists` | S3Verifier | Assert S3 object existence |
| `messageCount` | SQSVerifier | Assert approximate SQS message count |
| `messageBodyMatches` | SQSVerifier | Assert SQS message body content |
| `metricThreshold` | CloudWatchVerifier | Assert CloudWatch metric value threshold |
| `logGroupExists` | CloudWatchVerifier | Assert CloudWatch log group existence |
| `logContains` | LambdaVerifier | Assert CloudWatch log event pattern match |
| `durationWithin` | LambdaVerifier | Assert step duration within limit |
| `jobStatus` | GlueVerifier | Assert Glue job run status |
| `jobCompletedWithin` | GlueVerifier | Assert Glue job completes within timeout |

### Template Resolution

All action parameters support `{{ }}` template syntax:

| Prefix | Source | Example |
|---|---|---|
| `env.*` | Environment variable | `{{env.TEST_BUCKET}}` |
| `config.*` | Suite/case/step config (merged) | `{{config.region}}` |
| `shared.*` | Shared state from lifecycle hooks | `{{shared.bucketName}}` |
| `resolved.*` | Current data row field | `{{resolved.bucket}}` |
| `step.<name>.response.*` | Previous step's response | `{{step.upload-data.response.eTag}}` |

### Data-Driven Testing

Data files (JSON array or CSV) in `regression.data-dirs` (default: `test-data/`). Each row in the data file triggers one execution of each test case that references it via `dataSource`.

```
test-data/investor-pipeline.json:
[
  { "bucket": "test-bucket-1", "key": "data/file1.json", "content": "{\"id\": 1}" },
  { "bucket": "test-bucket-2", "key": "data/file2.json", "content": "{\"id\": 2}" }
]
```

CSV format (first row = headers):
```csv
bucket,key,content
test-bucket-1,data/file1.json,"{""id"": 1}"
```

## Configuration

### application.yml

```yaml
regression:
  aws:
    endpoint: http://localhost:4566      # AWS endpoint (localstack/Floci)
    region: us-east-1
    path-style-access: true
  suite-dirs:
    - test-suites                        # Directories to scan for suite JSON
  data-dirs:
    - test-data                          # Directories to scan for data files
  retention-days: 90                     # Default archival cutoff

spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/regression
    username: regression
    password: regression
```

### Persisted Config

Key-value config stored in the `regression_config` table, settable at runtime:

```
shell:>config --key default.bucket --value my-test-bucket
shell:>config
  default.bucket = my-test-bucket
```

## Shell Commands

| Command | Description |
|---|---|
| `run --suite <name> [--data <file>] [--quiet]` | Execute a test suite |
| `report --run-id <uuid> [--format text\|json\|html] [--output <path>]` | Generate report for a run |
| `list-runs [--limit N] [--suite <name>]` | List recent test runs |
| `list-suites` | List available test suites |
| `history --suite <name> [--limit N] [--days N]` | Show run history with sparklines |
| `inspect --run-id <uuid> [--case <name>] [--step <name>]` | Inspect run details as a tree |
| `compare --run-id <uuid> --run-id <uuid>` | Compare two test runs (regression detection) |
| `config [--key <k> --value <v>]` | View or set persisted config |
| `archive --older-than N` | Archive runs older than N days |
| `recover [--run-id <uuid>]` | Detect and recover stale RUNNING runs |

## How to Check Results

### Console Summary

After each `run`, a summary line is printed:

```
Suite: investor-pipeline  |  2/2 cases  |  2 passed  0 failed  0 error  0 skipped  |  234ms
Failure breakdown: 1 ASSERTION, 0 TIMEOUT, 0 ACTION_ERROR
```

### List Runs

```
shell:>list-runs --limit 5
Run ID                               Suite                Status     Start Time           Duration
------------------------------------ -------------------- ---------- -------------------- ----------
a1b2c3d4-...                         investor-pipeline    PASSED     2026-07-27T10:00:00   234ms
```

### Inspect Run

```
shell:>inspect --run-id a1b2c3d4-...
investor-pipeline (PASSED) [234ms]
├── create-bucket (PASSED) [120ms]
│   └── upload-data → PASSED [120ms]
├── verify-object (PASSED) [114ms]
│   └── check → PASSED [114ms]
```

### History (with sparklines)

```
shell:>history --suite investor-pipeline
investor-pipeline — last 10 runs (30 days)
Pass rate:  ▇▇▇▇▃▇▇▇▇▇  90% (9/10)
Duration:   ▁▁▁▁▃▁▁▃▁▇  median 215ms
```

### Compare Runs

```
shell:>compare --run-id a1b2... --run-id c3d4...
Comparing PASSED vs FAILED
Regression:  verify-object: PASSED → FAILED  (duration: +89ms)
Improvements: create-bucket: FAILED → PASSED  (duration: -12ms)
Regressions: 1, Improvements: 1
Duration: 234ms → 312ms (+78ms, +33%)
```

## How to Get Reports

### Text Report

```
shell:>report --run-id a1b2c3d4-...
Suite: investor-pipeline  |  PASSED  |  234ms
  create-bucket (PASSED) [120ms]
    └── upload-data → PASSED [120ms]
  verify-object (PASSED) [114ms]
    └── check → PASSED [114ms]
```

### JSON Report

```
shell:>report --run-id a1b2c3d4-... --format json
```

Outputs the full `ReportModel` as JSON with run metadata, case results, step results, and verifications.

### HTML Report

```
shell:>report --run-id a1b2c3d4-... --format html --output /tmp/report.html
```

Generates a standalone HTML page styled with inline CSS, rendered from `src/main/resources/jte/report.jte`.

## Lifecycle Hooks

Hooks run actions before/after suites and cases. Shared state set by `beforeAll`/`beforeEach` hooks propagates to `{{shared.*}}` templates.

```json
"beforeAll": [{
  "hookType": "beforeAll",
  "actions": [{ "actionType": "s3:putObject", "params": { ... } }],
  "stopOnFailure": true,
  "order": 0,
  "description": "Upload pipeline config"
}]
```

## Retries & Timeouts

Each step can specify:
- `retryCount` — retries on failure (default 0); steps marked `FLAKY` on success after retry
- `timeoutMs` — per-attempt timeout (default: no timeout)
- `flaky` (case-level) — marks the case as allowed to fail

## Archival

Old runs can be archived via stored procedure:

```
shell:>archive --older-than 90
```

This moves records older than N days to `*_archive` tables and deletes them from the main tables.
