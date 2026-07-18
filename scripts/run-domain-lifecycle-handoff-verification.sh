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

REPORT_ROOT="$ROOT_DIR/build/reports/domain-lifecycle-handoff-ci"
DISCOVERY_ROOT="$ROOT_DIR/regelsuche-discovery/build/reports/domain-lifecycle-handoff"
AUTOPILOT_ROOT="$ROOT_DIR/regelsuche-autopilot/build/reports/domain-lifecycle-handoff"
FIRST_ROOT="$REPORT_ROOT/first-run"
rm -rf "$REPORT_ROOT"
mkdir -p "$FIRST_ROOT"

set -o pipefail
./gradlew \
  :regelsuche-discovery:test \
  :regelsuche-autopilot:test \
  --tests de.regelsuche.discovery.domain.DiscoveryLifecycleHandoffTest \
  --tests de.regelsuche.experiments.autopilot.AutonomousProductionDomainHandoffAdapterTest \
  --rerun-tasks \
  --console=plain \
  2>&1 | tee "$REPORT_ROOT/gradle-first.log"

"$PYTHON" scripts/verify-domain-lifecycle-handoff.py \
  2>&1 | tee "$REPORT_ROOT/first-validation.log"
cp -a "$DISCOVERY_ROOT" "$FIRST_ROOT/discovery"
cp -a "$AUTOPILOT_ROOT" "$FIRST_ROOT/autopilot"

./gradlew \
  :regelsuche-discovery:test \
  :regelsuche-autopilot:test \
  --tests de.regelsuche.discovery.domain.DiscoveryLifecycleHandoffTest \
  --tests de.regelsuche.experiments.autopilot.AutonomousProductionDomainHandoffAdapterTest \
  --rerun-tasks \
  --console=plain \
  2>&1 | tee "$REPORT_ROOT/gradle-second.log"

"$PYTHON" scripts/verify-domain-lifecycle-handoff.py \
  2>&1 | tee "$REPORT_ROOT/second-validation.log"
diff -ru "$FIRST_ROOT/discovery" "$DISCOVERY_ROOT" \
  2>&1 | tee "$REPORT_ROOT/discovery-diff.log"
diff -ru "$FIRST_ROOT/autopilot" "$AUTOPILOT_ROOT" \
  2>&1 | tee "$REPORT_ROOT/autopilot-diff.log"

echo "OK: repeated domain lifecycle handoff and export evidence is byte-identical"
