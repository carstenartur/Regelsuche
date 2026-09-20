#!/usr/bin/env bash
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"
export PYTHONPATH="$repository_root/scripts"

# The preceding typed comparison step prepares this pinned environment and
# builds the shared Java classpath. Every v2 sequence still pays worker startup.
schema_python=build/typed-external-polynomial-venv/bin/python
"$schema_python" -m external_polynomial_comparison.run_schema \
  --classpath regelsuche-learning/build/external-polynomial-classpath.txt \
  --output build/reports/learned-schema-efficiency-v2
"$schema_python" -m external_polynomial_comparison.run_schema \
  --verify --output build/reports/learned-schema-efficiency-v2
