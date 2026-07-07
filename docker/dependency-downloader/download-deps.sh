#!/usr/bin/env bash
set -euo pipefail

MVN_REPO="https://repo1.maven.org/maven2"
TARGET_DIR="$(cd "$(dirname "$0")/target" && pwd)/deps/jars"

HADOOP_AWS_VERSION="3.3.6"
AWS_SDK_V2_VERSION="2.21.0"
POSTGRES_VERSION="42.5.4"

echo "=== Downloading Glue job dependencies ==="
echo "  Target: ${TARGET_DIR}"

mkdir -p "${TARGET_DIR}"

fetch() {
  local group="$1"
  local artifact="$2"
  local version="$3"
  local dest_name="${4:-${artifact}-${version}.jar}"
  local dest_path="${TARGET_DIR}/${dest_name}"
  local url="${MVN_REPO}/${group}/${artifact}/${version}/${artifact}-${version}.jar"

  if [ -f "${dest_path}" ] && [ -s "${dest_path}" ]; then
    return
  fi

  # Remove 0-byte partial from any previous failed attempt
  rm -f "${dest_path}"

  echo "  Downloading ${dest_name}"
  wget -q --show-progress "${url}" -O "${dest_path}"
}

echo "Downloading hadoop-aws ${HADOOP_AWS_VERSION}..."
fetch "org/apache/hadoop" "hadoop-aws" "${HADOOP_AWS_VERSION}"
# Spark 3.5.8 ships hadoop-client 3.3.4, but hadoop-aws 3.3.6 needs hadoop-common 3.3.6 APIs
fetch "org/apache/hadoop" "hadoop-client-api" "${HADOOP_AWS_VERSION}"
fetch "org/apache/hadoop" "hadoop-client-runtime" "${HADOOP_AWS_VERSION}"

# hadoop-aws 3.3.x references v1 SDK classes (credential providers, S3 model classes) at runtime.
# The v1 bundle shades its deps (Jackson, etc.) avoiding classpath conflicts with Spark.
fetch "com/amazonaws" "aws-java-sdk-bundle" "1.12.262"

echo "Downloading PostgreSQL JDBC driver ${POSTGRES_VERSION}..."
fetch "org/postgresql" "postgresql" "${POSTGRES_VERSION}"

echo "Downloading AWS SDK v2 ${AWS_SDK_V2_VERSION}..."

# Core modules
fetch "software/amazon/awssdk" "annotations"          "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-annotations-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "utils"                "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-utils-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "http-client-spi"      "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-http-client-spi-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "metrics-spi"          "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-metrics-spi-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "profiles"             "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-profiles-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "regions"              "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-regions-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "auth"                 "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-auth-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "sdk-core"             "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-sdk-core-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "json-utils"           "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-json-utils-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "third-party-jackson-core" "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-third-party-jackson-core-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "protocol-core"        "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-protocol-core-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "endpoints-spi"        "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-endpoints-spi-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "aws-core"             "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-aws-core-${AWS_SDK_V2_VERSION}.jar"

# Protocol implementations
fetch "software/amazon/awssdk" "aws-query-protocol"   "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-aws-query-protocol-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "aws-json-protocol"    "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-aws-json-protocol-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "aws-xml-protocol"     "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-aws-xml-protocol-${AWS_SDK_V2_VERSION}.jar"

# HTTP auth modules (needed by all services in 2.21.x)
fetch "software/amazon/awssdk" "identity-spi"         "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-identity-spi-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "http-auth-spi"        "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-http-auth-spi-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "http-auth"            "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-http-auth-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "checksums-spi"        "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-checksums-spi-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "checksums"            "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-checksums-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "http-auth-aws"        "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-http-auth-aws-${AWS_SDK_V2_VERSION}.jar"

# S3-specific transitive deps
fetch "software/amazon/awssdk" "arns"                 "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-arns-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "crt-core"             "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-crt-core-${AWS_SDK_V2_VERSION}.jar"

# reactive-streams (dep of http-auth-spi, not bundled in Spark)
fetch "org/reactivestreams" "reactive-streams"        "1.0.4"

# Service-specific modules
fetch "software/amazon/awssdk" "s3"                   "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-s3-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "glue"                 "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-glue-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "ssm"                  "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-ssm-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "sts"                  "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-sts-${AWS_SDK_V2_VERSION}.jar"
fetch "software/amazon/awssdk" "apache-client"        "${AWS_SDK_V2_VERSION}" "aws-sdk-v2-apache-client-${AWS_SDK_V2_VERSION}.jar"

echo ""
echo ""
echo "=== Download complete ==="
echo "JARs in ${TARGET_DIR}:"
ls -1sh "${TARGET_DIR}"/*.jar 2>/dev/null | sort
echo ""
echo "Total size: $(du -sh "${TARGET_DIR}" | cut -f1)"
echo "Total JAR count: $(ls -1 "${TARGET_DIR}"/*.jar 2>/dev/null | wc -l)"
