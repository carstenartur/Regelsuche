#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "A reachable Docker daemon is required for comparative benchmark reproduction." >&2
  exit 2
fi
if ! command -v z3 >/dev/null 2>&1; then
  echo "Z3 4.8.12 is required for the checkout-local comparative benchmark." >&2
  exit 2
fi
Z3_VERSION="$(z3 -version)"
if [[ "$Z3_VERSION" != *"Z3 version 4.8.12"* ]]; then
  echo "Expected Z3 4.8.12, found: $Z3_VERSION" >&2
  exit 2
fi

VENV="${REGELSUCHE_COMPARATIVE_VENV:-$ROOT_DIR/build/comparative-verification-venv}"
PYTHON="$VENV/bin/python"
PIP="$VENV/bin/pip"
if [[ ! -x "$PYTHON" ]]; then
  python3 -m venv "$VENV"
fi
if ! "$PYTHON" -c 'import importlib.metadata as m; raise SystemExit(0 if m.version("sympy") == "1.14.0" and m.version("jsonschema") == "4.25.1" else 1)' 2>/dev/null; then
  "$PIP" install --disable-pip-version-check --quiet \
    sympy==1.14.0 \
    jsonschema==4.25.1
fi

"$PYTHON" -c 'import sympy; print("sympy=" + sympy.__version__)'
echo "$Z3_VERSION"

REPORT_ROOT="$ROOT_DIR/build/reports/comparative-ci"
RUN_A="$REPORT_ROOT/run-a"
RUN_B="$REPORT_ROOT/run-b"
RUN_DOCKER="$REPORT_ROOT/run-docker"
rm -rf "$RUN_A" "$RUN_B" "$RUN_DOCKER"
mkdir -p "$REPORT_ROOT"

export REGELSUCHE_SYMPY_PYTHON="$PYTHON"
set -o pipefail
./gradlew :regelsuche-benchmarks:test --console=plain \
  2>&1 | tee "$REPORT_ROOT/gradle.log"
./gradlew :regelsuche-benchmarks:writeComparativeBenchmark \
  -PcomparativeBenchmarkOutput="$RUN_A" \
  --console=plain \
  2>&1 | tee -a "$REPORT_ROOT/gradle.log"
./gradlew :regelsuche-benchmarks:writeComparativeBenchmark \
  -PcomparativeBenchmarkOutput="$RUN_B" \
  --console=plain \
  2>&1 | tee -a "$REPORT_ROOT/gradle.log"

"$PYTHON" scripts/verify-comparative-benchmark.py --root "$RUN_A" \
  2>&1 | tee "$REPORT_ROOT/run-a-validation.log"
"$PYTHON" scripts/verify-comparative-benchmark.py --root "$RUN_B" \
  2>&1 | tee "$REPORT_ROOT/run-b-validation.log"
diff -ru "$RUN_A" "$RUN_B" \
  2>&1 | tee "$REPORT_ROOT/gradle-repeat-diff.log"

docker build \
  -f Dockerfile.comparative-benchmarks \
  -t regelsuche-comparative-benchmarks:verification . \
  2>&1 | tee "$REPORT_ROOT/docker-build.log"

mkdir -p "$RUN_DOCKER"
chmod 0777 "$RUN_DOCKER"
docker run --rm \
  -v "$RUN_DOCKER:/output" \
  regelsuche-comparative-benchmarks:verification \
  /output \
  2>&1 | tee "$REPORT_ROOT/docker-run.log"

"$PYTHON" scripts/verify-comparative-benchmark.py --root "$RUN_DOCKER" \
  2>&1 | tee "$REPORT_ROOT/run-docker-validation.log"
diff -ru "$RUN_A" "$RUN_DOCKER" \
  2>&1 | tee "$REPORT_ROOT/docker-diff.log"

echo "OK: repeated Gradle and pinned Docker comparative evidence are byte-identical"
