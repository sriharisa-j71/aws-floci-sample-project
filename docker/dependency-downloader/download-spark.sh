#!/usr/bin/env bash
set -euo pipefail

SPARK_VERSION="${SPARK_VERSION:-3.5.8}"
HADOOP_SHORT="${HADOOP_SHORT:-3}"
TARGET_DIR="$(cd "$(dirname "$0")/target" && pwd)"

# --- Spark version resolution ---
LOCAL_SPARK="${LOCAL_SPARK_HOME:-}"

if [ -z "$LOCAL_SPARK" ]; then
  for candidate in /usr/local/spark-*-bin-hadoop* /opt/spark-*-bin-hadoop*; do
    if [ -d "$candidate" ]; then
      LOCAL_SPARK="$candidate"
      break
    fi
  done
fi

if [ -n "$LOCAL_SPARK" ] && [ -d "$LOCAL_SPARK" ]; then
  LOCAL_DIRNAME="$(basename "$(readlink -f "$LOCAL_SPARK")")"
  LOCAL_VERSION="$(echo "$LOCAL_DIRNAME" | sed 's/^spark-\(.*\)-bin-hadoop[0-9]*$/\1/')"
  LOCAL_HADOOP="$(echo "$LOCAL_DIRNAME" | sed 's/^spark-.*-bin-hadoop\([0-9]*\)$/\1/')"
  SPARK_VERSION="$LOCAL_VERSION"
  HADOOP_SHORT="$LOCAL_HADOOP"
  echo "Using local Spark: ${LOCAL_SPARK} (v${SPARK_VERSION}, hadoop ${HADOOP_SHORT})"
fi

SPARK_DIR="spark-${SPARK_VERSION}-bin-hadoop${HADOOP_SHORT}"
EXTRACTED="${TARGET_DIR}/spark-dist/${SPARK_DIR}"

if [ -d "${EXTRACTED}" ]; then
  echo "Spark already extracted at ${EXTRACTED} — skipping"
  exit 0
fi

# --- Use local Spark copy ---
if [ -n "${LOCAL_SPARK:-}" ]; then
  echo "Copying from local Spark installation..."
  mkdir -p "${TARGET_DIR}/spark-dist"
  cp -a "$(readlink -f "$LOCAL_SPARK")" "${EXTRACTED}"

  echo "Pruning unnecessary components..."
  cd "${EXTRACTED}"
  rm -rf python pyspark R yarn examples data licenses kubernetes

  echo "Done: ${EXTRACTED}"
  echo "  Next: docker build --build-arg SPARK_VERSION=${SPARK_VERSION} --build-arg HADOOP_SHORT=${HADOOP_SHORT} ..."
  exit 0
fi

# --- Fall back to download ---
TARBALL="${SPARK_DIR}.tgz"
URL="https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/${TARBALL}"
mkdir -p "${TARGET_DIR}/spark-raw"
cd "${TARGET_DIR}/spark-raw"

if [ ! -f "${TARBALL}" ]; then
  echo "Downloading Spark ${SPARK_VERSION}..."
  wget -q --show-progress "${URL}"
else
  echo "Spark tarball already cached at ${TARGET_DIR}/spark-raw/${TARBALL}"
fi

echo "Extracting to ${TARGET_DIR}/spark-dist/..."
rm -rf "${TARGET_DIR}/spark-dist"
mkdir -p "${TARGET_DIR}/spark-dist"
tar xzf "${TARBALL}" -C "${TARGET_DIR}/spark-dist/"

echo "Pruning unnecessary components from extracted Spark..."
cd "${EXTRACTED}"
rm -rf python pyspark R yarn examples data licenses kubernetes

echo "Done: ${EXTRACTED}"
du -sh "${EXTRACTED}" | awk '{print "  Extracted dist: " $1}'
