#!/usr/bin/env bash
set -euo pipefail

project=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.ci/lean-proof" && pwd)
test "$(cat "$project/lean-toolchain")" = 'leanprover/lean4:v4.19.0'
lean_dir=$(mktemp -d "${1:?toolchain scratch directory required}/lean.XXXXXX")
archive="$lean_dir/lean.tar.zst"
trap 'rm -f "$archive"' EXIT

# Fetch the exact release without discovering assets through the release page.
download=(curl --fail --silent --show-error --location --retry 2 --retry-max-time 120
  --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 120 --output "$archive")
"${download[@]}" 'https://github.com/leanprover/lean4/releases/download/v4.19.0/lean-4.19.0-linux.tar.zst' ||
  "${download[@]}" -H 'Accept: application/octet-stream' 'https://api.github.com/repos/leanprover/lean4/releases/assets/250981932'
printf '%s  %s\n' '6fe3ce97a58f44e2b3567d455b994eacec5bfe9ae7774f2a573444480ba813fe' "$archive" | sha256sum --check >&2
tar --zstd -xf "$archive" -C "$lean_dir" --strip-components=1 --no-same-owner
printf '%s\n' "$lean_dir/bin"
