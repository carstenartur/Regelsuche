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

for private_file in "${validation_private}" "${final_test_private}"; do
  if [[ ! -f ${private_file} ]]; then
    echo "private held-out bundle is not a regular file: ${private_file}" >&2
    exit 2
  fi
done

exec ./gradlew \
  --no-daemon \
  -I gradle/flagship-rewrite-program-freeze.init.gradle \
  :app:freezeFlagshipRewriteProgram \
  -PflagshipRepositoryCommit="${repository_commit}" \
  -PflagshipValidationPrivate="${validation_private}" \
  -PflagshipFinalTestPrivate="${final_test_private}" \
  -PflagshipFreezeOutput="${output_directory}"
