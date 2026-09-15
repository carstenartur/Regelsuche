#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "A reachable Docker daemon is required for fullCheck." >&2
  exit 2
fi

RELEASE_VERIFIER_JAVA="${REGELSUCHE_RELEASE_VERIFIER_JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
if [[ -z "$RELEASE_VERIFIER_JAVA" || ! -x "$RELEASE_VERIFIER_JAVA" ]]; then
  echo "Java 25 is required: set JAVA_HOME or REGELSUCHE_RELEASE_VERIFIER_JAVA to its launcher." >&2
  exit 2
fi
if ! RELEASE_JAVA_SETTINGS="$("$RELEASE_VERIFIER_JAVA" -XshowSettings:properties -version 2>&1)"; then
  echo "Cannot inspect the required Java 25 launcher: $RELEASE_VERIFIER_JAVA" >&2
  exit 2
fi
if [[ ! "$RELEASE_JAVA_SETTINGS" =~ (^|$'\n')[[:blank:]]*java[.]specification[.]version[[:blank:]]*=[[:blank:]]*25[[:blank:]]*($'\n'|$) ]]; then
  echo "Java 25 is required for release-readiness verification: $RELEASE_VERIFIER_JAVA" >&2
  exit 2
fi
RELEASE_VERIFIER_LIB="$ROOT_DIR/regelsuche-release/build/install/regelsuche-release/lib"
if [[ ! -d "$RELEASE_VERIFIER_LIB" ]]; then
  echo "Missing :regelsuche-release:installDist libraries: $RELEASE_VERIFIER_LIB" >&2
  exit 2
fi

LOCAL_ROOT="regelsuche-release/build/reports/release-readiness-qualified"
DOCKER_ROOT="$ROOT_DIR/build/release-readiness-docker-output"
mkdir -p build/logs

"$RELEASE_VERIFIER_JAVA" --enable-native-access=ALL-UNNAMED -cp "$RELEASE_VERIFIER_LIB/*" \
  de.regelsuche.release.ReleaseReadinessEvidenceVerifier \
  --root "$LOCAL_ROOT" --schemas "$ROOT_DIR/docs/schemas" \
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

"$RELEASE_VERIFIER_JAVA" --enable-native-access=ALL-UNNAMED -cp "$RELEASE_VERIFIER_LIB/*" \
  de.regelsuche.release.ReleaseReadinessEvidenceVerifier \
  --root "$DOCKER_ROOT" --schemas "$ROOT_DIR/docs/schemas" \
  2>&1 | tee build/logs/release-readiness-docker-validation.log

diff -ru "$LOCAL_ROOT" "$DOCKER_ROOT" \
  2>&1 | tee build/logs/release-readiness-diff.log

echo "OK: Gradle and Docker release-readiness evidence are byte-identical"
