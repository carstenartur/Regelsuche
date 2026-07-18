#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "A reachable Docker daemon is required for release-readiness reproduction." >&2
  exit 2
fi

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
  :app:test \
  --tests de.regelsuche.docs.HiddenRulePilotCampaignTest \
  :regelsuche-release:runQualifiedReleaseReadinessWithHiddenRuleEvidence \
  --console=plain \
  2>&1 | tee build/logs/release-readiness-gradle.log

LOCAL_ROOT="regelsuche-release/build/reports/release-readiness-qualified"
"$PYTHON" scripts/verify-release-readiness-evidence.py \
  --root "$LOCAL_ROOT" \
  2>&1 | tee build/logs/release-readiness-local-validation.log

docker build \
  -f Dockerfile.release-readiness \
  -t regelsuche-release-readiness:verification . \
  2>&1 | tee build/logs/release-readiness-docker-build.log

DOCKER_ROOT="$ROOT_DIR/build/release-readiness-docker-output"
rm -rf "$DOCKER_ROOT"
mkdir -p "$DOCKER_ROOT"
chmod 0777 "$DOCKER_ROOT"

docker run --rm \
  -v "$DOCKER_ROOT:/output" \
  -v "$ROOT_DIR/app/build/reports/hidden-rule-pilot/report.json:/input/hidden-rule-report.json:ro" \
  regelsuche-release-readiness:verification \
  /output \
  --hidden-rule-report /input/hidden-rule-report.json \
  --qualify-candidate \
  --require-ready \
  2>&1 | tee build/logs/release-readiness-docker-run.log

"$PYTHON" scripts/verify-release-readiness-evidence.py \
  --root "$DOCKER_ROOT" \
  2>&1 | tee build/logs/release-readiness-docker-validation.log

diff -ru "$LOCAL_ROOT" "$DOCKER_ROOT" \
  2>&1 | tee build/logs/release-readiness-diff.log

echo "OK: Gradle and Docker release-readiness evidence are byte-identical"
