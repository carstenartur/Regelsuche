#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p build/logs
set -o pipefail
./gradlew --no-configuration-cache :app:jmh --console=plain \
  2>&1 | tee build/logs/jmh-benchmark.log

python3 scripts/verify-jmh-benchmark.py \
  --result app/build/reports/jmh/result.json \
  --badge-output public/dev/bench/badge.json \
  2>&1 | tee build/logs/jmh-benchmark-validation.log
