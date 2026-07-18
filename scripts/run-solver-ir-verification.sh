#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

VENV="${REGELSUCHE_VERIFICATION_VENV:-$ROOT_DIR/build/verification-venv}"
PYTHON="$VENV/bin/python"
PIP="$VENV/bin/pip"

if [[ ! -x "$PYTHON" ]]; then
  python3 -m venv "$VENV"
fi
if ! "$PYTHON" -c 'import importlib.metadata as m; raise SystemExit(0 if m.version("jsonschema") == "4.25.1" else 1)' 2>/dev/null; then
  "$PIP" install --disable-pip-version-check --quiet jsonschema==4.25.1
fi

mkdir -p build/logs
set -o pipefail
./gradlew \
  :regelsuche-solver-ir:test \
  :regelsuche-solver-ir:writeSolverIrExample \
  --console=plain \
  2>&1 | tee build/logs/solver-ir-gradle.log

"$PYTHON" scripts/verify-solver-ir-evidence.py \
  2>&1 | tee build/logs/solver-ir-validation.log
