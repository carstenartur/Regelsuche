#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  cat >&2 <<'USAGE'
usage: scripts/freeze-flagship-rewrite-program.sh \
  <repository-commit> \
  <validation-private.json> \
  <final-test-private.json> \
  <public-output-directory>
USAGE
  exit 2
fi

repository_commit=$1
validation_private=$2
final_test_private=$3
output_directory=$4

if [[ ! ${repository_commit} =~ ^[0-9a-f]{40}$ ]]; then
  echo "repository commit must be a lowercase 40-character Git commit" >&2
  exit 2
fi

repository_root=$(git rev-parse --show-toplevel)
actual_commit=$(git -C "${repository_root}" rev-parse HEAD)
if [[ ${actual_commit} != "${repository_commit}" ]]; then
  echo "repository commit differs from current HEAD: expected=${repository_commit}, actual=${actual_commit}" >&2
  exit 2
fi

if [[ -n $(git -C "${repository_root}" status --porcelain --untracked-files=all) ]]; then
  echo "flagship freeze requires a clean checkout" >&2
  exit 2
fi

for private_file in "${validation_private}" "${final_test_private}"; do
  if [[ ! -f ${private_file} ]]; then
    echo "private held-out bundle is not a regular file: ${private_file}" >&2
    exit 2
  fi
done

python3 - "${repository_root}" "${validation_private}" "${final_test_private}" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
for raw in sys.argv[2:]:
    private_file = Path(raw).resolve()
    if private_file == root or root in private_file.parents:
        raise SystemExit(
            f"private held-out bundle must remain outside the checkout: {private_file}"
        )
PY

if [[ -e ${output_directory} && ! -d ${output_directory} ]]; then
  echo "flagship freeze output exists and is not a directory: ${output_directory}" >&2
  exit 2
fi
if [[ -d ${output_directory} ]] \
    && [[ -n $(find "${output_directory}" -mindepth 1 -maxdepth 1 -print -quit) ]]; then
  echo "flagship freeze output directory must be absent or empty: ${output_directory}" >&2
  exit 2
fi

exec ./gradlew \
  --no-daemon \
  -I gradle/flagship-rewrite-program-freeze.init.gradle \
  :app:freezeFlagshipRewriteProgram \
  -PflagshipRepositoryCommit="${repository_commit}" \
  -PflagshipValidationPrivate="$(realpath "${validation_private}")" \
  -PflagshipFinalTestPrivate="$(realpath "${final_test_private}")" \
  -PflagshipFreezeOutput="${output_directory}"
