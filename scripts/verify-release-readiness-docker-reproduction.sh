#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "A reachable Docker daemon is required for fullCheck." >&2
  exit 2
fi

PYTHON="${REGELSUCHE_VERIFICATION_PYTHON:-$ROOT_DIR/build/verification-venv/bin/python}"
if [[ ! -x "$PYTHON" ]]; then
  echo "Pinned verification Python is missing: $PYTHON" >&2
  exit 2
fi

LOCAL_ROOT="regelsuche-release/build/reports/release-readiness-qualified"
DOCKER_ROOT="$ROOT_DIR/build/release-readiness-docker-output"
mkdir -p build/logs

"$PYTHON" scripts/verify-release-readiness-evidence.py --root "$LOCAL_ROOT" \
  2>&1 | tee build/logs/release-readiness-local-validation.log

docker build \
  -f Dockerfile.release-readiness \
  -t regelsuche-release-readiness:verification . \
  2>&1 | tee build/logs/release-readiness-docker-build.log

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

"$PYTHON" scripts/verify-release-readiness-evidence.py --root "$DOCKER_ROOT" \
  2>&1 | tee build/logs/release-readiness-docker-validation.log

diff -ru "$LOCAL_ROOT" "$DOCKER_ROOT" \
  2>&1 | tee build/logs/release-readiness-diff.log

echo "OK: Gradle and Docker release-readiness evidence are byte-identical"
