#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

# Keep qualification assertions and orchestration in the checkout, matching
# the existing run-... authority adapters rather than embedding them in CI.
python3 -m unittest discover -s scripts -p test_released_sdk_maven_consumers.py -v
exec python3 scripts/verify-released-sdk-maven-consumers.py "$@"
