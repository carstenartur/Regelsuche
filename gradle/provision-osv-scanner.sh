#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
manifest=config/quality/vulnerability-evidence/osv-scanner.json
digest=$(jq -er '.sha256 | capture("^sha256:(?<hex>[0-9a-f]{64})$").hex' "$manifest")
size=$(jq -er '.bytes | select(type == "number" and . > 0)' "$manifest")
browser_url=$(jq -er '.url | select(test("^https://github[.]com/google/osv-scanner/releases/download/v[0-9.]+/osv-scanner_linux_amd64$"))' "$manifest")
release=$(jq -er '.release.path' "$manifest")
release_digest=$(jq -er '.release.sha256 | capture("^sha256:(?<hex>[0-9a-f]{64})$").hex' "$manifest")
printf '%s  %s\n' "$release_digest" "$release" | sha256sum --check --status
asset_id=$(jq -er --arg url "$browser_url" --arg hash "sha256:$digest" --argjson size "$size" '
  [.assets[] | select(.browser_download_url == $url and .digest == $hash and .size == $size)]
  | if length == 1 then .[0].id else error("expected one pinned scanner asset") end
  | select(type == "number" and . > 0 and floor == .)' "$release")

directory="build/tools/osv-scanner/$digest"
binary="$directory/osv-scanner"
if [[ -f "$binary" && -x "$binary" && ! -L "$binary" ]] &&
  printf '%s  %s\n' "$digest" "$binary" | sha256sum --check --status; then
  exit 0
fi

mkdir -p "$directory"
temporary=$(mktemp "$directory/download.XXXXXX")
trap 'rm -f "$temporary"' EXIT
download=(curl --fail --silent --show-error --location --retry 2 --retry-max-time 120
  --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 120
  --max-filesize "$size" --output "$temporary")
"${download[@]}" "$browser_url" ||
  "${download[@]}" -H 'Accept: application/octet-stream' "https://api.github.com/repos/google/osv-scanner/releases/assets/$asset_id"
test "$(wc -c < "$temporary")" -eq "$size"
printf '%s  %s\n' "$digest" "$temporary" | sha256sum --check
chmod 0755 "$temporary"
mv -T "$temporary" "$binary"
