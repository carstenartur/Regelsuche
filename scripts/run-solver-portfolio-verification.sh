#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v z3 >/dev/null 2>&1; then
  echo "Z3 is required for the canonical solver-portfolio evidence run." >&2
  echo "Install z3 and rerun: bash scripts/run-solver-portfolio-verification.sh" >&2
  exit 2
fi
z3 -version

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
  :regelsuche-solver-portfolio:test \
  :regelsuche-solver-portfolio:writeSolverPortfolioExample \
  --console=plain \
  2>&1 | tee build/logs/solver-portfolio-gradle.log

./gradlew \
  :app:test \
  --tests de.regelsuche.proof.OpenTargetProofPortfolioIntegrationTest \
  --console=plain \
  2>&1 | tee -a build/logs/solver-portfolio-gradle.log

"$PYTHON" scripts/verify-solver-portfolio-evidence.py \
  2>&1 | tee build/logs/solver-portfolio-validation.log
