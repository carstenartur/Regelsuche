#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

if [[ "$*" == *"separateJmhAuthority"* ]]; then
  echo "The isolated JMH authority must not disable its own benchmark graph." >&2
  exit 2
fi

exec ./gradlew \
  --no-daemon \
  --no-configuration-cache \
  jmhAuthority \
  --console=plain \
  --stacktrace \
  "$@"
