# Architecture Diagram

## Data Flow

```mermaid
flowchart LR
  subgraph DataSources["Data Sources"]
    PG[("PostgreSQL\nemp table")]
  end

  subgraph Compute["Compute Layer"]
    GLUE["AWS Glue Job\nScala/Spark\nEmpToS3Core"]
    L1["Lambda: employee-file-handler\nJava 17\nS3Event → SQS"]
    L2["Lambda: employee-sal-processor\nJava 17\nSQS → WireMock"]
  end

  subgraph Storage["Storage & Messaging"]
    S3_OUT[("S3: emp-output\npipe-delimited CSV")]
    SQS[("SQS: emp-processing\n1 msg / employee")]
    SSM[("SSM Parameter Store\n/emp/api/endpoint\n/emp/sqs/queue-url\n/emp/api/employee-check-enabled")]
  end

  subgraph ExternalAPI["External API"]
    WM["WireMock :8080\nGET /employee/{id}"]
  end

  subgraph Logging["Logging (current)"]
    L1_LOG["stdout / stderr\ncontext.getLogger"]
    L2_LOG["stdout / stderr\ncontext.getLogger"]
    GLUE_LOG["println"]
    S3_LOG[("CloudWatch Logs\nauto (AWS deploy)")]
  end

  PG -->|JDBC query| GLUE
  GLUE -->|GET /employee/{id}| WM
  GLUE -->|pipe-delimited CSV| S3_OUT
  S3_OUT -->|S3 ObjectCreated:*.csv| L1
  L1 -->|1 SQS message / record| SQS
  SQS -->|SQSEvent| L2
  L2 -->|GET /employee/{id}| WM
  L1 -.->|reads at init| SSM
  L2 -.->|reads at init| SSM
  GLUE -.->|reads at init| SSM

  L1 -.-> L1_LOG -.-> S3_LOG
  L2 -.-> L2_LOG -.-> S3_LOG
  GLUE -.-> GLUE_LOG -.-> S3_LOG
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
    IS -->|aws sqs| SQS[SQS Queue]
    IS -->|aws s3| S3_BUCKETS[S3 Buckets]
    IS -->|aws lambda| LAMBDAS[Lambda Functions]
    IS -->|aws s3api| S3_NOTIF[S3 Notification]
    IS -->|aws lambda create-event-source-mapping| SQS_MAP[SQS → Lambda]
  end

  subgraph AWSDeploy["Real AWS (OpenTofu)"]
    TF["tofu apply"]
    TF --> TF_IAM[IAM Roles + Policies]
    TF --> TF_S3[S3 Buckets\n+ Script & JAR uploads]
    TF --> TF_GLUE[Glue Job]
    TF --> TF_SSM[SSM Parameters]
    TF --> TF_SQS[SQS Queue]
    TF --> TF_L1[Lambda: file-handler]
    TF --> TF_L2[Lambda: sal-processor]
    TF --> TF_S3N[S3 → Lambda notification]
    TF --> TF_SQSM[SQS → Lambda mapping]
    TF --> TF_RUN[terraform_data\n: glue start-job-run]
  end

  subgraph BuildChain["Build Artifacts"]
    SBT["sbt assembly\nScala/Spark JAR"]
    MVN1["mvn package\nLambda 1 JAR"]
    MVN2["mvn package\nLambda 2 JAR"]
    DOCKER["docker build\nglue-scala-minimal"]
  end
```

## Component Map

```mermaid
mindmap
  root((aws-gluejob-floci))
    GlueJob
      Main.scala
      EmpToS3Core.scala
      EmpToS3Job.scala
      build.sbt
    Lambda1_S3ToSQS
      S3ToSqsLambda.java
      EmployeeRecord.java
      pom.xml
    Lambda2_SQSProcessor
      SqsProcessorLambda.java
      EmployeeRecord.java
      pom.xml
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
    Config
      docker-compose.yml
      Dockerfile
      .gitignore
      .dockerignore
    WireMock
      employee-check-*.json
    Test
      EmpToS3JobSimulation.scala
```

## Sequence (End-to-End Flow)

```mermaid
sequenceDiagram
  participant PG as PostgreSQL
  participant GLUE as Glue Job (Spark)
  participant WM as WireMock
  participant S3 as S3 (emp-output)
  participant L1 as Lambda: file-handler
  participant SQS as SQS (emp-processing)
  participant L2 as Lambda: sal-processor

  GLUE->>PG: JDBC SELECT emp ORDER BY emp_id
  PG-->>GLUE: 5 employee rows
  loop For each employee (coalesce 1)
    GLUE->>WM: GET /employee/{id}
    WM-->>GLUE: {"exists":true,...}
  end
  GLUE->>S3: Write pipe-delimited CSV

  Note over S3,L1: Floci/AWS detects ObjectCreated:*.csv
  S3-->>L1: Trigger S3Event
  L1->>S3: Read CSV
  L1->>SQS: Publish 1 msg/employee (5 total)

  Note over SQS,L2: Floci/AWS detects SQS messages
  SQS-->>L2: Trigger SQSEvent
  loop For each message
    L2->>WM: GET /employee/{id}
    WM-->>L2: {"exists":true,...}
    L2->>SQS: DeleteMessage
  end
```
