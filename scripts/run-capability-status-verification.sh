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
  :regelsuche-release:runQualifiedReleaseReadinessWithHiddenRuleEvidence \
  :regelsuche-release:runDomainGenericQualification \
  --rerun-tasks \
  --console=plain \
  2>&1 | tee build/logs/capability-status-evidence.log

"$PYTHON" scripts/generate-capability-status.py \
  --repository-root . \
  --release-report regelsuche-release/build/reports/release-readiness-qualified/release-readiness-report.json \
  --release-run regelsuche-release/build/reports/release-readiness-qualified/release-readiness-run.json \
  --domain-report regelsuche-release/build/reports/domain-generic-qualification/qualification-report.json \
  --domain-run regelsuche-release/build/reports/domain-generic-qualification/qualification-run.json \
  --repository-revision WORKTREE \
  --json-output docs/generated/capability-status.json \
  --markdown-output docs/generated/capability-status.md \
  --check \
  --check-docs \
  2>&1 | tee build/logs/capability-status-generation.log

"$PYTHON" scripts/verify-capability-status.py \
  2>&1 | tee build/logs/capability-status-validation.log
