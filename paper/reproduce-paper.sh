#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

rm -rf paper/generated
mkdir -p paper/generated

./gradlew --no-daemon verifyCandidateIndependentBenchmarkExecutionV2

python3 paper/generate-benchmark-results.py \
  --execution build/reports/candidate-independent-benchmark-execution-v2/first/run.json \
  --output paper/generated/candidate-independent-benchmark.md

python3 scripts/verify-paper-foundation.py \
  --manifest paper/paper-artifact-manifest.json \
  --schema docs/schemas/regelsuche-paper-artifact-manifest-v2.schema.json \
  --benchmark-execution build/reports/candidate-independent-benchmark-execution-v2/first/run.json \
  --output paper/generated
