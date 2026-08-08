#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 6 ]]; then
  cat >&2 <<'USAGE'
usage: scripts/freeze-flagship-rewrite-program-public.sh \
  <repository-commit> \
  <validation-commitment.json> \
  <validation-split-references.json> \
  <final-test-commitment.json> \
  <final-test-split-references.json> \
  <public-output-directory>
USAGE
  exit 2
fi

repository_commit=$1
validation_commitment=$2
validation_references=$3
final_test_commitment=$4
final_test_references=$5
output_directory=$6

if [[ ! ${repository_commit} =~ ^[0-9a-f]{40}$ ]]; then
  echo "repository commit must be a lowercase 40-character Git commit" >&2
  exit 2
fi

repository_root=$(git rev-parse --show-toplevel)
actual_commit=$(git -C "${repository_root}" rev-parse HEAD)
if [[ ${actual_commit} != "${repository_commit}" ]]; then
  echo "repository commit differs from current HEAD:" \
    "expected=${repository_commit}, actual=${actual_commit}" >&2
  exit 2
fi

resolve_path() {
  python3 -c \
    'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve())' \
    "$1"
}

validation_commitment=$(resolve_path "${validation_commitment}")
validation_references=$(resolve_path "${validation_references}")
final_test_commitment=$(resolve_path "${final_test_commitment}")
final_test_references=$(resolve_path "${final_test_references}")
output_directory=$(resolve_path "${output_directory}")

if [[ -n $(git -C "${repository_root}" status --porcelain --untracked-files=all) ]]; then
  echo "public flagship freeze reproduction requires a clean checkout" >&2
  exit 2
fi

for public_file in \
    "${validation_commitment}" \
    "${validation_references}" \
    "${final_test_commitment}" \
    "${final_test_references}"; do
  if [[ ! -f "${public_file}" || -L "${public_file}" ]]; then
    echo "public flagship input is not a regular non-symlink file: ${public_file}" >&2
    exit 2
  fi
done

if [[ -e "${output_directory}" && ! -d "${output_directory}" ]]; then
  echo "flagship freeze output exists and is not a directory: ${output_directory}" >&2
  exit 2
fi
if [[ -d "${output_directory}" ]] \
    && [[ -n $(find "${output_directory}" -mindepth 1 -maxdepth 1 -print -quit) ]]; then
  echo "flagship freeze output directory must be absent or empty: ${output_directory}" >&2
  exit 2
fi

exec ./gradlew \
  --no-daemon \
  -I gradle/flagship-rewrite-program-public-freeze.init.gradle \
  :app:reproduceFlagshipRewriteProgramFreeze \
  -PflagshipRepositoryCommit="${repository_commit}" \
  -PflagshipValidationCommitment="${validation_commitment}" \
  -PflagshipValidationReferences="${validation_references}" \
  -PflagshipFinalTestCommitment="${final_test_commitment}" \
  -PflagshipFinalTestReferences="${final_test_references}" \
  -PflagshipFreezeOutput="${output_directory}"
