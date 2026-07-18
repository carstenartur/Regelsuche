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

REPORT_ROOT="$ROOT_DIR/build/reports/domain-generic-qualification-ci"
EVIDENCE_ROOT="$ROOT_DIR/regelsuche-release/build/reports/domain-generic-qualification"
FIRST_ROOT="$REPORT_ROOT/first-run"
rm -rf "$REPORT_ROOT"
mkdir -p "$REPORT_ROOT"

set -o pipefail
./gradlew \
  :regelsuche-release:test \
  --tests de.regelsuche.release.DomainGenericQualificationRunnerTest \
  :regelsuche-release:runDomainGenericQualification \
  --rerun-tasks \
  --console=plain \
  2>&1 | tee "$REPORT_ROOT/gradle-first.log"

"$PYTHON" scripts/verify-domain-generic-qualification.py \
  2>&1 | tee "$REPORT_ROOT/first-validation.log"
cp -a "$EVIDENCE_ROOT" "$FIRST_ROOT"

./gradlew \
  :regelsuche-release:runDomainGenericQualification \
  --rerun-tasks \
  --console=plain \
  2>&1 | tee "$REPORT_ROOT/gradle-second.log"

"$PYTHON" scripts/verify-domain-generic-qualification.py \
  2>&1 | tee "$REPORT_ROOT/second-validation.log"
diff -ru "$FIRST_ROOT" "$EVIDENCE_ROOT" \
  2>&1 | tee "$REPORT_ROOT/repeated-run-diff.log"

echo "OK: repeated domain-generic qualification evidence is byte-identical"
