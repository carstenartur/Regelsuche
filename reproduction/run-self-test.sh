#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

REVISION="${REGELSUCHE_REPOSITORY_REVISION:-$(git rev-parse HEAD)}"
RELEASE_TAG="${REGELSUCHE_RELEASE_TAG:-development-${REVISION}}"
RELEASE_TAG_STATUS="${REGELSUCHE_RELEASE_TAG_STATUS:-DEVELOPMENT_REVISION}"
ARCHITECTURE="${REGELSUCHE_REPRODUCTION_ARCHITECTURE:-linux/amd64}"
BUILD_ROOT="${REGELSUCHE_REPRODUCTION_BUILD_ROOT:-${ROOT}/build}"

EXPECTED_WALKTHROUGH="${BUILD_ROOT}/independent-walkthrough"
ARTIFACT_A="${BUILD_ROOT}/independent-reproduction-a"
ARTIFACT_B="${BUILD_ROOT}/independent-reproduction-b"
ARCHIVE_A="${BUILD_ROOT}/independent-reproduction-a.tar.gz"
ARCHIVE_B="${BUILD_ROOT}/independent-reproduction-b.tar.gz"
SELF_TEST_OUTPUT="${BUILD_ROOT}/independent-reproduction-self-test"
SECOND_RECEIPT="${BUILD_ROOT}/reproduction-receipt-second-metadata.json"
MISSING_RECEIPT="${BUILD_ROOT}/reproduction-receipt-missing-input.json"
CORRUPT_RECEIPT="${BUILD_ROOT}/reproduction-receipt-unreadable-manifest.json"

log() {
  printf '\n==> %s\n' "$*"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command is unavailable: $1" >&2
    exit 2
  }
}

require_command git
require_command java
require_command python3
require_command docker

python3 - <<'PY'
try:
    import jsonschema  # noqa: F401
except ImportError as exception:
    raise SystemExit(
        "jsonschema is required; install jsonschema==4.25.1 before running "
        "reproduction/run-self-test.sh"
    ) from exception
PY

if ! docker info >/dev/null 2>&1; then
  echo "Docker is required for the isolated reproduction self-test." >&2
  exit 2
fi

log "Validate frozen wrapper, container and executable inputs"
grep -qx \
  'distributionSha256Sum=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f' \
  gradle/wrapper/gradle-wrapper.properties
grep -qx \
  'FROM eclipse-temurin:21.0.11_10-jdk-noble@sha256:35685c7e23352983a48882d97cd9875f5284c228db71d1e2476e5e6c1bab1080 AS build' \
  reproduction/Dockerfile.reproduction
bash -n reproduction/reproduce.sh
python3 -m py_compile \
  scripts/build-independent-reproduction-artifact.py \
  scripts/verify-independent-reproduction.py

log "Generate the qualified expected walkthrough"
rm -rf "${EXPECTED_WALKTHROUGH}"
REGELSUCHE_REPOSITORY_REVISION="${REVISION}" ./gradlew \
  :regelsuche-release:runAutonomousDiscoveryWalkthrough \
  -PwalkthroughOutput="${EXPECTED_WALKTHROUGH}" \
  --rerun-tasks --console=plain \
  2>&1 | tee "${BUILD_ROOT}/independent-walkthrough.log"
python3 scripts/verify-autonomous-discovery-walkthrough.py \
  --root "${EXPECTED_WALKTHROUGH}" \
  --schema docs/schemas/regelsuche-autonomous-discovery-result-card-v1.schema.json

log "Build the frozen artifact twice byte-for-byte"
rm -rf "${ARTIFACT_A}" "${ARTIFACT_B}" "${ARCHIVE_A}" "${ARCHIVE_B}"
COMMON_ARGS=(
  --repository-root .
  --walkthrough-root "${EXPECTED_WALKTHROUGH}"
  --source-revision "${REVISION}"
  --release-tag "${RELEASE_TAG}"
  --release-tag-status "${RELEASE_TAG_STATUS}"
  --architecture "${ARCHITECTURE}"
)
python3 scripts/build-independent-reproduction-artifact.py \
  "${COMMON_ARGS[@]}" \
  --output-directory "${ARTIFACT_A}" \
  --archive-output "${ARCHIVE_A}" \
  | tee "${BUILD_ROOT}/artifact-build-a.log"
python3 scripts/build-independent-reproduction-artifact.py \
  "${COMMON_ARGS[@]}" \
  --output-directory "${ARTIFACT_B}" \
  --archive-output "${ARCHIVE_B}" \
  | tee "${BUILD_ROOT}/artifact-build-b.log"
diff -ru "${ARTIFACT_A}" "${ARTIFACT_B}"
cmp "${ARCHIVE_A}" "${ARCHIVE_B}"

log "Validate manifests, file roots and fixed archive identity"
for artifact_root in "${ARTIFACT_A}" "${ARTIFACT_B}"; do
  python3 scripts/verify-independent-reproduction.py \
    verify-artifact --root "${artifact_root}"
done
python3 - "${ARTIFACT_A}" "${ARCHIVE_A}" <<'PY'
import hashlib
import json
from pathlib import Path
import sys
import tarfile

root = Path(sys.argv[1])
archive = Path(sys.argv[2])
manifest = json.loads((root / "artifact-manifest.json").read_text())
assert manifest["artifactStatus"] == "DEVELOPMENT_READY_FOR_INDEPENDENT_EXECUTION"
assert manifest["source"]["releaseTagStatus"] == "DEVELOPMENT_REVISION"
assert manifest["externalAttestationStatus"] == "NOT_COLLECTED"
assert manifest["declaredEnvironment"]["evaluatedRunNetworkPolicy"] == "DISABLED"
assert manifest["declaredEnvironment"]["javaImageIndexDigest"] == (
    "sha256:35685c7e23352983a48882d97cd9875f5284c228db71d1e2476e5e6c1bab1080"
)
assert manifest["declaredEnvironment"]["gradleDistributionSha256"] == (
    "bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f"
)
with tarfile.open(archive, "r:gz") as bundle:
    names = [member.name for member in bundle.getmembers()]
assert names
assert all(
    name == "regelsuche-independent-reproduction"
    or name.startswith("regelsuche-independent-reproduction/")
    for name in names
)
assert not any("/../" in name or name.startswith("/") for name in names)
print("archiveSha256=sha256:" + hashlib.sha256(archive.read_bytes()).hexdigest())
PY

log "Run the one-command reproduction in the isolated container"
rm -rf "${SELF_TEST_OUTPUT}"
"${ARTIFACT_A}/reproduce.sh" \
  --output "${SELF_TEST_OUTPUT}" \
  --environment-id maintainer-local-self-test \
  2>&1 | tee "${BUILD_ROOT}/independent-reproduction-self-test.log"

log "Verify exact receipt and non-semantic metadata stability"
python3 scripts/verify-independent-reproduction.py verify-receipt \
  --root "${ARTIFACT_A}" \
  --receipt "${SELF_TEST_OUTPUT}/reproduction-receipt.json"
python3 scripts/verify-independent-reproduction.py compare \
  --root "${ARTIFACT_A}" \
  --observed "${SELF_TEST_OUTPUT}/observed" \
  --receipt "${SECOND_RECEIPT}" \
  --environment-id maintainer-local-self-test \
  --started-at 2030-01-01T00:00:00Z \
  --finished-at 2030-01-01T00:00:01Z \
  --container-image-id sha256:non-semantic-local-image-id \
  --execution-exit-code 0 \
  --platform alternate-non-semantic-platform \
  --docker-version alternate-non-semantic-version
python3 scripts/verify-independent-reproduction.py verify-receipt \
  --root "${ARTIFACT_A}" \
  --receipt "${SECOND_RECEIPT}"
python3 - "${SELF_TEST_OUTPUT}/reproduction-receipt.json" "${SECOND_RECEIPT}" <<'PY'
import json
import sys
first = json.load(open(sys.argv[1], encoding="utf-8"))
second = json.load(open(sys.argv[2], encoding="utf-8"))
assert first["reproductionStatus"] == "EXACT_BYTE_REPRODUCED"
assert second["reproductionStatus"] == "EXACT_BYTE_REPRODUCED"
assert first["semanticReceiptHash"] == second["semanticReceiptHash"]
assert first["reproducerAttestationHash"] == second["reproducerAttestationHash"]
assert first["contentHash"] != second["contentHash"]
assert first["externalAttestationStatus"] == "NOT_COLLECTED"
PY

log "Retain NOT_REPRODUCED receipts for missing and unreadable inputs"
MISSING_ROOT="$(mktemp -d)"
CORRUPT_ROOT="$(mktemp -d)"
trap 'rm -rf "${MISSING_ROOT}" "${CORRUPT_ROOT}"' EXIT
cp -a "${ARTIFACT_A}/." "${MISSING_ROOT}/"
rm "${MISSING_ROOT}/expected/result-card.md"
set +e
python3 scripts/verify-independent-reproduction.py compare \
  --root "${MISSING_ROOT}" \
  --observed "${SELF_TEST_OUTPUT}/observed" \
  --receipt "${MISSING_RECEIPT}" \
  --environment-id maintainer-local-negative-test \
  --started-at 2030-01-01T00:00:00Z \
  --finished-at 2030-01-01T00:00:01Z \
  --execution-exit-code 125
missing_rc=$?
set -e
test "${missing_rc}" -ne 0
python3 scripts/verify-independent-reproduction.py verify-receipt \
  --root "${MISSING_ROOT}" \
  --receipt "${MISSING_RECEIPT}"

cp -a "${ARTIFACT_A}/." "${CORRUPT_ROOT}/"
printf '{not-json\n' > "${CORRUPT_ROOT}/artifact-manifest.json"
set +e
python3 scripts/verify-independent-reproduction.py compare \
  --root "${CORRUPT_ROOT}" \
  --observed "${SELF_TEST_OUTPUT}/observed" \
  --receipt "${CORRUPT_RECEIPT}" \
  --environment-id maintainer-local-negative-test \
  --started-at 2030-01-01T00:00:00Z \
  --finished-at 2030-01-01T00:00:01Z \
  --execution-exit-code 125
corrupt_rc=$?
set -e
test "${corrupt_rc}" -ne 0
python3 scripts/verify-independent-reproduction.py verify-receipt \
  --root "${CORRUPT_ROOT}" \
  --receipt "${CORRUPT_RECEIPT}"

python3 - "${MISSING_RECEIPT}" "${CORRUPT_RECEIPT}" <<'PY'
import json
import sys
missing = json.load(open(sys.argv[1], encoding="utf-8"))
corrupt = json.load(open(sys.argv[2], encoding="utf-8"))
assert missing["reproductionStatus"] == "NOT_REPRODUCED"
assert "missing artifact file: expected/result-card.md" in (
    missing["semanticComparison"]["inputArtifactFailures"]
)
assert corrupt["reproductionStatus"] == "NOT_REPRODUCED"
assert corrupt["artifactManifestHashSource"] == "FILE_SHA256_FALLBACK"
assert any(
    value.startswith("artifact manifest is unreadable:")
    for value in corrupt["semanticComparison"]["inputArtifactFailures"]
)
PY

log "Independent reproduction self-test completed"
printf 'revision=%s\nartifact=%s\nreceipt=%s\n' \
  "${REVISION}" "${ARCHIVE_A}" \
  "${SELF_TEST_OUTPUT}/reproduction-receipt.json"
