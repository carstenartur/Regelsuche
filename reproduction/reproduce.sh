#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT="$(cd "${ROOT}/.." && pwd)/independent-reproduction-output"
ENVIRONMENT_ID="anonymous-reproducer"
VERIFY_ONLY=false

usage() {
  cat <<'EOF'
Usage: ./reproduce.sh --output PATH [--environment-id ID]
       ./reproduce.sh --verify-only

The immutable artifact is verified before execution. The container build uses
only the bound source archive and digest-pinned definition. The evaluated run
uses a non-root user, Gradle offline mode and Docker --network none. A canonical
receipt is retained on successful reproduction and on evaluated-run failure.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      [[ $# -ge 2 ]] || { echo "--output requires a path" >&2; exit 2; }
      OUTPUT="$2"
      shift 2
      ;;
    --environment-id)
      [[ $# -ge 2 ]] || { echo "--environment-id requires a value" >&2; exit 2; }
      ENVIRONMENT_ID="$2"
      shift 2
      ;;
    --verify-only)
      VERIFY_ONLY=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ -n "${ENVIRONMENT_ID// }" ]] || { echo "environment id must not be blank" >&2; exit 2; }
if [[ ${#ENVIRONMENT_ID} -gt 256 ]]; then
  echo "environment id must contain at most 256 characters" >&2
  exit 2
fi

if [[ "${VERIFY_ONLY}" == true ]]; then
  exec python3 "${ROOT}/scripts/verify-independent-reproduction.py" \
    verify-artifact --root "${ROOT}"
fi

OUTPUT="$(python3 - "${ROOT}" "${OUTPUT}" <<'PY'
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1]).resolve()
raw_output = Path(sys.argv[2]).expanduser()
if raw_output.exists() and raw_output.is_symlink():
    raise SystemExit("output directory must not be a symbolic link")
out = raw_output.resolve()
home = Path.home().resolve()
filesystem_root = Path(out.anchor)
if (
    out == filesystem_root
    or out == home
    or out == root
    or root in out.parents
    or out in root.parents
):
    raise SystemExit(
        "output directory must be separate from filesystem root, home and artifact root"
    )
marker = out / ".regelsuche-independent-reproduction-output"
if out.exists():
    if not out.is_dir():
        raise SystemExit("output path must be a directory")
    entries = list(out.iterdir())
    if entries and not marker.is_file():
        raise SystemExit(
            "refusing to replace a non-empty output directory without the reproduction marker"
        )
    for child in entries:
        if child.is_symlink() or child.is_file():
            child.unlink()
        else:
            shutil.rmtree(child)
else:
    out.mkdir(parents=True)
marker.write_text(
    "regelsuche.independent-reproduction-output/v1\n",
    encoding="utf-8",
)
print(out)
PY
)"

mkdir -p "${OUTPUT}/observed" "${OUTPUT}/logs"
chmod 0777 "${OUTPUT}/observed"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

MANIFEST_RC=0
python3 "${ROOT}/scripts/verify-independent-reproduction.py" verify-artifact \
  --root "${ROOT}" \
  >"${OUTPUT}/logs/verify-input.log" 2>&1 || MANIFEST_RC=$?

REVISION="0000000000000000000000000000000000000000"
ARCHITECTURE="linux/amd64"
if [[ ${MANIFEST_RC} -eq 0 ]]; then
  REVISION="$(python3 - "${ROOT}/artifact-manifest.json" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding='utf-8'))['source']['revision'])
PY
)"
  ARCHITECTURE="$(python3 - "${ROOT}/artifact-manifest.json" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding='utf-8'))['declaredEnvironment']['architecture'])
PY
)"
fi

IMAGE_TAG="regelsuche-independent-reproduction:${REVISION}"
IMAGE_ID="NOT_AVAILABLE"
EXECUTION_RC=0

if [[ ${MANIFEST_RC} -ne 0 ]]; then
  EXECUTION_RC=125
  printf 'Input artifact verification failed; evaluated run was not started.\n' \
    >"${OUTPUT}/logs/evaluated-run.log"
else
  BUILD_RC=0
  docker build \
    --pull=false \
    --no-cache \
    --platform "${ARCHITECTURE}" \
    --target independent-reproduction \
    --build-arg "REGELSUCHE_REPOSITORY_REVISION=${REVISION}" \
    -f "${ROOT}/environment/Dockerfile.reproduction" \
    -t "${IMAGE_TAG}" \
    "${ROOT}" \
    >"${OUTPUT}/logs/container-build.log" 2>&1 || BUILD_RC=$?
  if [[ ${BUILD_RC} -ne 0 ]]; then
    EXECUTION_RC=125
    printf 'Container build failed with exit code %s.\n' "${BUILD_RC}" \
      >"${OUTPUT}/logs/evaluated-run.log"
  else
    IMAGE_ID="$(docker image inspect --format '{{.Id}}' "${IMAGE_TAG}" 2>/dev/null || printf 'NOT_AVAILABLE')"
    docker run --rm \
      --platform "${ARCHITECTURE}" \
      --network none \
      --cap-drop ALL \
      --security-opt no-new-privileges \
      --pids-limit 2048 \
      -v "${OUTPUT}/observed:/out" \
      "${IMAGE_TAG}" \
      >"${OUTPUT}/logs/evaluated-run.log" 2>&1 || EXECUTION_RC=$?
  fi
fi

FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
PLATFORM="$(uname -srm 2>/dev/null || printf 'UNKNOWN')"
DOCKER_VERSION="$(docker version --format '{{.Server.Version}}' 2>/dev/null || printf 'UNKNOWN')"

COMPARE_RC=0
python3 "${ROOT}/scripts/verify-independent-reproduction.py" compare \
  --root "${ROOT}" \
  --observed "${OUTPUT}/observed" \
  --receipt "${OUTPUT}/reproduction-receipt.json" \
  --environment-id "${ENVIRONMENT_ID}" \
  --started-at "${STARTED_AT}" \
  --finished-at "${FINISHED_AT}" \
  --container-image-id "${IMAGE_ID}" \
  --execution-exit-code "${EXECUTION_RC}" \
  --platform "${PLATFORM}" \
  --docker-version "${DOCKER_VERSION}" \
  | tee "${OUTPUT}/logs/comparison.log" || COMPARE_RC=$?

exit "${COMPARE_RC}"
