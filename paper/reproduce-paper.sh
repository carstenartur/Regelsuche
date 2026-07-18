#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

python3 scripts/verify-paper-foundation.py \
  --manifest paper/paper-artifact-manifest.json \
  --schema docs/schemas/regelsuche-paper-artifact-manifest-v1.schema.json \
  --output paper/generated
