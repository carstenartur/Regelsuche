#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

if [[ "$*" == *"separateSympyRuntimeAuthority"* ]]; then
  echo "The isolated SymPy runtime authority must not disable its own test graph." >&2
  exit 2
fi
if [[ -n "${REGELSUCHE_SEPARATE_SYMPY_RUNTIME_AUTHORITY:-}" ]]; then
  echo "The isolated SymPy runtime authority must not inherit the separation authorization marker." >&2
  exit 2
fi

exec ./gradlew \
  --no-daemon \
  --no-configuration-cache \
  :regelsuche-math-sympy:sympyRuntimeAuthority \
  --console=plain \
  --stacktrace \
  "$@"
