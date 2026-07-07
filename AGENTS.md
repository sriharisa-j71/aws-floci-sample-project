# Session Memory

## Project Structure
- Infrastructure: `docker-compose.yml`, `docker/` — Dockerfile, dependency-downloader, entrypoint
- Glue job: Scala code in `glue-job/`, main class `com.example.Main`
- Scripts: `scripts/run-glue-job.sh` (CI entry), `scripts/glue-runner.sh` (spark-submit inside container)

## Pre-build Steps
Before `docker build`, run from `docker/dependency-downloader/`:
1. `bash download-spark.sh` — uses local Spark from `/usr/local/spark-*-bin-hadoop*` if present (copies and prunes python/pyspark/R/yarn/examples/data/licenses/kubernetes), otherwise downloads Spark 3.5.8 tarball; extracts to `target/spark-dist/`. Set `LOCAL_SPARK_HOME` env var to override auto-detection.
2. `bash download-deps.sh` — downloads JARs via `wget` from Maven Central to `target/deps/jars/` (skips if already cached)
3. `bash download-all.sh` — runs both above

## Completed Changes
1. **Moved root Dockerfile to `docker/`** — build context is `docker/`, uses `.dockerignore`
2. **Removed `rds` module** from dependency list
3. **Kept `amazoncorretto:11-alpine`** (JDK, not JRE)
4. **Replaced Maven dependency download with shell script** — deleted `pom.xml`, created `download-deps.sh` using `wget` directly from Maven Central
5. **Upgraded `hadoop-aws` from 3.3.4 → 3.3.6**
6. **Kept `aws-java-sdk-bundle`** (268 MB, v1) — **required by hadoop-aws 3.3.x** at runtime (credential providers, S3 model classes). The bundle shades its deps (Jackson, etc.) avoiding classpath conflicts with Spark. The app code still uses SDK v2 exclusively.
7. **Replaced Spark's hadoop-client 3.3.4 → 3.3.6** to match hadoop-aws version (Dockerfile removes old, deps copy adds new)
8. **Added SDK v2 modules** with full transitive deps: `annotations`, `utils`, `http-client-spi`, `metrics-spi`, `profiles`, `regions`, `auth`, `sdk-core`, `json-utils`, `third-party-jackson-core`, `protocol-core`, `endpoints-spi`, `aws-core`, `aws-query-protocol`, `aws-json-protocol`, `aws-xml-protocol`, `identity-spi`, `http-auth-spi`, `http-auth`, `checksums-spi`, `checksums`, `http-auth-aws`, `arns`, `crt-core`, `s3`, `glue`, `ssm`, `sts`, `iam`, `kms`, `dynamodb`, `apache-client`, `postgresql`, `reactive-streams`
9. **Set `spark.hadoop.fs.s3a.sdk.version=v2`** in `glue-runner.sh`
10. **Dockerfile now copies from extracted `spark-dist/`** — no tarball layer in image (saves ~280 MB), `COPY dependency-downloader/target/spark-dist/ /opt/`
11. **JAR COPY simplified** — single `COPY dependency-downloader/target/deps/jars/ /opt/spark/jars/` instead of per-JAR COPY lines

## Build Commands
```bash
cd docker/dependency-downloader
bash download-all.sh
cd ..
docker build -t glue-scala-minimal:latest .
```

## Image Stats
- Base: `amazoncorretto:11-alpine`
- Spark dist (extracted, pruned): ~324 MB (3.5.8) on disk
- Additional JARs (SDK v2 + postgres + hadoop-aws + hadoop-client 3.3.6 + v1 bundle): ~343 MB (39 JARs)
- Total image: ~985 MB (3.5.8)
