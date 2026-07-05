# Architecture

## Data Flow

```mermaid
flowchart LR
  subgraph DataSources["Data Sources"]
    PG[("PostgreSQL\n7 tables")]
    CUR_FILE["Currency Rates File\npipe-delimited CSV"]
  end

  subgraph Compute["Compute Layer"]
    GLUE["AWS Glue Job\nScala/Spark\nInvestorToS3Core"]
    L1["Lambda: investor-file-handler\nJava 17\nS3Event → SQS"]
    L2["Lambda: investor-sal-processor\nJava 17\nSQS → WireMock + DB"]
    L3["Lambda: currency-refresh\nGo (provided.al2023)\nS3 → pgx batch"]
    L4["Lambda: risk-score-calculator\nGo (provided.al2023)\nS3 → DB scores"]
  end

  subgraph Storage["Storage & Messaging"]
    S3_OUT[("S3: investor-output\npipe-delimited CSV")]
    S3_CUR[("S3: currency-rates-input")]
    SQS[("SQS: investor-processing\n1 msg / investor")]
    DLQ[("SQS: DLQ\nfailed investors")]
    SSM[("SSM Parameter Store\n/investor/api/endpoint\n/investor/sqs/queue-url")]
  end

  subgraph ExternalAPI["Mock API"]
    WM["WireMock :8080\nGET /investor/{id}\nGET /segment/{segment}\nPOST /risk-score/{id}"]
  end

  PG -->|JDBC query| GLUE
  GLUE -->|GET /segment/{segment}| WM
  GLUE -->|pipe-delimited CSV| S3_OUT
  S3_OUT -->|S3 ObjectCreated:*.csv| L1
  L1 -->|1 SQS message / record| SQS
  SQS -->|SQSEvent| L2
  L2 -->|GET /investor/{id}| WM
  L2 -->|query risk scores| PG
  L2 -->|POST /risk-score/{id}| WM
  L2 -->|exists:false → DLQ| DLQ

  S3_CUR -->|S3 ObjectCreated:*| L3
  L3 -->|TRUNCATE + batch INSERT| PG
  S3_CUR -->|S3 ObjectCreated:*| L4
  L4 -->|read investors + rates| PG
  L4 -->|upsert daily_risk_scores| PG

  L1 -.->|reads at init| SSM
  L2 -.->|reads at init| SSM
  GLUE -.->|reads at init| SSM
```

## Deployment & Infrastructure

```mermaid
flowchart TD
  subgraph LocalDev["Local Dev (Floci + Docker Compose)"]
    DC["docker compose up"]
    DC --> FLOCI["Floci :4566\nAWS API mock"]
    DC --> PG["PostgreSQL :5432"]
    DC --> WM["WireMock :8080"]
    DC --> GR["glue-runner\nSpark container"]
    DC --> IS["infra-setup\naws-cli container"]
    IS -->|aws ssm| SSM[SSM Parameters]
    IS -->|aws sqs| SQS[SQS Queue + DLQ]
    IS -->|aws s3| S3_BUCKETS[S3 Buckets]
    IS -->|aws lambda|     LAMBDAS[Lambda Functions\nfile-handler, sal-processor\ncurrency-refresh\nrisk-score-calculator]
    IS -->|aws s3api| S3_NOTIF[S3 Notifications\ninvestor-output + currency-rates-input]
    IS -->|aws lambda create-event-source-mapping| SQS_MAP[SQS → Lambda mapping]
  end

  subgraph AWSDeploy["Real AWS (OpenTofu)"]
    TF["tofu apply"]
    TF --> TF_IAM[IAM Roles + Policies]
    TF --> TF_S3[S3 Buckets\n+ Script & JAR uploads]
    TF --> TF_GLUE[Glue Job]
    TF --> TF_SSM[SSM Parameters]
    TF --> TF_SQS[SQS Queue + DLQ]
    TF --> TF_L1[Lambda: file-handler]
    TF --> TF_L2[Lambda: sal-processor]
    TF --> TF_S3N[S3 → Lambda notification]
    TF --> TF_SQSM[SQS → Lambda mapping]
    TF --> TF_CW[CloudWatch Log Groups]
    TF --> TF_RUN[terraform_data\n: glue start-job-run]
  end

  subgraph BuildChain["Build Artifacts"]
    SBT["sbt assembly\nScala/Spark JAR"]
    MVN1["mvn package\nLambda 1 JAR"]
    MVN2["mvn package\nLambda 2 JAR"]
    GO_BUILD1["go build + zip\ncurrency-refresh bootstrap"]
    GO_BUILD2["go build + zip\nrisk-score-calculator bootstrap"]
    DOCKER["docker build\nglue-scala-minimal"]
  end
```

## Component Map

```mermaid
mindmap
  root((aws-gluejob-floci))
    GlueJob
      Main.scala
      InvestorToS3Core.scala
      build.sbt
    Lambda_FileHandler
      S3ToSqsLambda.java
      InvestorRecord.java
      pom.xml
    Lambda_SALProcessor
      SqsProcessorLambda.java
      InvestorRecord.java
      pom.xml
      Lambda_CurrencyRefresh
      main.go
      go.mod
      build.sh
    Lambda_RiskScoreCalculator
      main.go
      go.mod
      build.sh
    Infrastructure
      infra/main.tf
      infra/variables.tf
      infra/outputs.tf
      infra/terraform.tf
      infra/floci.tfvars
    Scripts
      build-lambdas.sh
      glue-runner.sh
      run-glue-job.sh
      setup-floci.sh
      setup-ssm.sh
      rds-setup.sh
      search-logs.sh
    Config
      docker-compose.yml
      Dockerfile
      .gitignore
      .dockerignore
    WireMock
      investor-check-*.json
      investor-check-segment.json
      risk-score-save.json
    Tools
      generate-data/main.go
      generate-currency-file/main.go
    Test
      EmpToS3JobSimulation.scala
```

## Sequence (End-to-End Flow)

```mermaid
sequenceDiagram
  participant PG as PostgreSQL
  participant GEN as Go Generator
  participant GLUE as Glue Job (Spark)
  participant WM as WireMock
  participant S3 as S3 (investor-output)
  participant L1 as Lambda: file-handler
  participant SQS as SQS (investor-processing)
  participant L2 as Lambda: sal-processor
  participant DLQ as DLQ

  GEN->>PG: INSERT N investors + risks + investments + liabilities
  Note over GEN,PG: Bulk CopyFrom in transactions

  GLUE->>PG: JDBC SELECT investors WHERE segment='Wealth'
  PG-->>GLUE: M investor rows
  GLUE->>WM: GET /segment/{segment}
  WM-->>GLUE: {"exists":true,"segment":"Wealth","status":"active"}
  GLUE->>S3: Write pipe-delimited CSV (coalesce 1)

  Note over S3,L1: Floci/AWS detects ObjectCreated:*.csv
  S3-->>L1: Trigger S3Event
  L1->>S3: Read CSV
  L1->>SQS: Publish 1 msg/investor (M total)

  Note over SQS,L2: Floci/AWS detects SQS messages
  SQS-->>L2: Trigger SQSEvent

  loop For each message
    L2->>WM: GET /investor/{id}
    alt exists: true (seed IDs 1-5)
      WM-->>L2: {"exists":true,"customer_id":N}
      L2->>SQS: DeleteMessage
    else exists: false (generated IDs 6+)
      WM-->>L2: {"exists":false}
      L2-->>DLQ: BatchItemFailure → DLQ
    end
  end

  Note over S3,DLQ: Currency refresh (parallel flow)
  participant CS3 as S3 (currency-rates-input)
  participant L3 as Lambda: currency-refresh
  participant L4 as Lambda: risk-score-calculator
  participant DRS as daily_risk_scores table

  GEN->>CS3: Upload rates file
  CS3-->>L3: Trigger S3Event
  L3->>CS3: Download file
  L3->>PG: TRUNCATE currency_rates
  L3->>PG: Batch INSERT N rates

  Note over CS3,L4: Parallel trigger (same S3 event)
  CS3-->>L4: Trigger S3Event
  L4->>PG: Load currency rates + all investors
  L4->>PG: Load investments & liabilities per investor
  Note over L4: Compute composite score\nprofile×0.30 + investment×0.25 + liability×0.45
  L4->>DRS: Upsert daily_risk_scores (1 row / investor)
```
