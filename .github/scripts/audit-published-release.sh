#!/usr/bin/env bash
set -euo pipefail

VERSION=${1:-}
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: $0 X.Y.Z" >&2
  exit 2
fi

for command in \
    gh jq curl sha256sum unzip tar java base64 awk grep sed sort wc \
    timeout cut paste tr mv basename head mktemp python3 cmp; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing post-release audit prerequisite: $command" >&2
    exit 2
  fi
done

REPOSITORY=${REGELSUCHE_RELEASE_AUDIT_REPOSITORY:-${GITHUB_REPOSITORY:-carstenartur/Regelsuche}}
ROOT=${REGELSUCHE_REPOSITORY_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
TAG="v${VERSION}"
SERIES=${VERSION%.*}
EXPECTED_ROOT="regelsuche-${VERSION}/"
REPORT_DIR="$ROOT/build/reports/release-audit"
REPORT="$REPORT_DIR/${VERSION}.json"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/regelsuche-release-audit-${VERSION}.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/assets" "$TMP/tagged" "$TMP/smoke" "$TMP/zenodo-pages" "$REPORT_DIR"

fail() {
  echo "Post-release audit failed: $*" >&2
  exit 1
}

require_equal() {
  local expected=$1
  local actual=$2
  local message=$3
  [[ "$actual" == "$expected" ]] ||
    fail "$message (expected '$expected', got '$actual')"
}

[[ "$REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
  fail "Invalid GitHub repository identity: $REPOSITORY"
[[ -f "$ROOT/pom.xml" ]] ||
  fail "Repository root has no pom.xml: $ROOT"

fetch_tagged_file() {
  local path=$1
  local target=$2
  gh api \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' \
    "repos/${REPOSITORY}/contents/${path}?ref=${TAG}" \
    --jq '.content' |
    tr -d '\n' |
    base64 --decode > "$target"
  [[ -s "$target" ]] ||
    fail "Tagged file is missing or empty: $path"
}

validate_archive() {
  local format=$1
  local archive=$2
  local listing=$3
  python3 - "$format" "$archive" "$EXPECTED_ROOT" "$listing" <<'PY'
import stat
import sys
import tarfile
import zipfile
from pathlib import PurePosixPath

archive_format, archive_path, expected_root, listing_path = sys.argv[1:]
names = []
seen = set()

def retain(name):
    if not name or "\x00" in name or "\\" in name:
        raise SystemExit(f"unsafe archive entry name: {name!r}")
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise SystemExit(f"archive entry escapes its root: {name}")
    if not name.startswith(expected_root):
        raise SystemExit(
            f"archive entry lies outside {expected_root}: {name}")
    if name in seen:
        raise SystemExit(f"duplicate archive entry: {name}")
    seen.add(name)
    names.append(name)

if archive_format == "zip":
    with zipfile.ZipFile(archive_path) as archive:
        for info in archive.infolist():
            retain(info.filename)
            mode = (info.external_attr >> 16) & 0xFFFF
            kind = stat.S_IFMT(mode)
            if kind == stat.S_IFLNK:
                raise SystemExit(
                    f"ZIP symbolic link is forbidden: {info.filename}")
            if info.is_dir():
                if kind not in (0, stat.S_IFDIR):
                    raise SystemExit(
                        f"ZIP directory has unsafe type: {info.filename}")
            elif kind not in (0, stat.S_IFREG):
                raise SystemExit(
                    f"ZIP special or linked entry is forbidden: "
                    f"{info.filename}")
elif archive_format == "tar":
    with tarfile.open(archive_path) as archive:
        for member in archive.getmembers():
            retain(member.name)
            if member.issym() or member.islnk():
                raise SystemExit(
                    f"TAR symbolic/hard link is forbidden: {member.name}")
            if member.isdev() or member.isfifo():
                raise SystemExit(
                    f"TAR device/FIFO entry is forbidden: {member.name}")
            if not (member.isfile() or member.isdir()):
                raise SystemExit(
                    f"TAR special entry is forbidden: {member.name}")
else:
    raise SystemExit(f"unsupported archive format: {archive_format}")

if not names:
    raise SystemExit(f"archive is empty: {archive_path}")
with open(listing_path, "w", encoding="utf-8", newline="\n") as output:
    output.write("\n".join(names))
    output.write("\n")
PY
}

extract_zip_safely() {
  local archive=$1
  local destination=$2
  python3 - "$archive" "$destination" "$EXPECTED_ROOT" <<'PY'
import shutil
import stat
import sys
import zipfile
from pathlib import Path, PurePosixPath

archive_path, destination_path, expected_root = sys.argv[1:]
destination = Path(destination_path).resolve()
destination.mkdir(parents=True, exist_ok=True)
seen = set()

with zipfile.ZipFile(archive_path) as archive:
    for info in archive.infolist():
        name = info.filename
        path = PurePosixPath(name)
        if (
            not name
            or "\x00" in name
            or "\\" in name
            or path.is_absolute()
            or ".." in path.parts
            or not name.startswith(expected_root)
            or name in seen
        ):
            raise SystemExit(f"unsafe ZIP extraction entry: {name!r}")
        seen.add(name)
        mode = (info.external_attr >> 16) & 0xFFFF
        kind = stat.S_IFMT(mode)
        if kind == stat.S_IFLNK:
            raise SystemExit(f"ZIP symbolic link is forbidden: {name}")
        target = destination.joinpath(*path.parts).resolve()
        try:
            target.relative_to(destination)
        except ValueError as exception:
            raise SystemExit(
                f"ZIP extraction target escapes destination: {name}"
            ) from exception
        if info.is_dir():
            if kind not in (0, stat.S_IFDIR):
                raise SystemExit(f"ZIP directory has unsafe type: {name}")
            target.mkdir(parents=True, exist_ok=True)
            continue
        if kind not in (0, stat.S_IFREG):
            raise SystemExit(
                f"ZIP special or linked entry is forbidden: {name}")
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(info) as source, open(target, "xb") as output:
            shutil.copyfileobj(source, output)
PY
}

run_smoke() {
  local name=$1
  shift
  local output="$TMP/smoke/${name}.txt"
  if ! env \
      -u NEO4J_URI \
      -u NEO4J_USER \
      -u NEO4J_PASSWORD \
      timeout 120 "$@" > "$output" 2>&1; then
    cat "$output" >&2 || true
    fail "Released-product smoke failed: $name"
  fi
  printf '%s' "$output"
}

zenodo_total() {
  jq -r '
    if (.hits.total | type) == "number" then
      .hits.total
    elif (.hits.total.value | type) == "number" then
      .hits.total.value
    else
      .hits.hits | length
    end
  ' "$1"
}

fetch_zenodo_version_lineage() {
  local seed_doi=$1
  local target=$2
  local metadata_target=$3
  local seed_record=${seed_doi##*.}
  local seed_file="$TMP/zenodo-seed.json"
  local latest_file="$TMP/zenodo-latest.json"
  local page=1
  local page_size=25
  local max_pages=10
  local seen=0
  local total=-1
  local page_file page_count versions_url latest_url parent_url
  local parent_doi_url concept_doi

  [[ "$seed_record" =~ ^[0-9]+$ ]] ||
    fail "Tagged README DOI does not end in a numeric Zenodo record ID"

  curl --fail --silent --show-error --location \
    --retry 5 --retry-delay 1 --retry-max-time 30 \
    -H 'Accept: application/json' \
    -H 'User-Agent: Regelsuche-published-release-audit/1' \
    "https://zenodo.org/api/records/${seed_record}" \
    > "$seed_file"

  require_equal "$seed_doi" \
    "$(jq -r '.pids.doi.identifier // .doi // empty' "$seed_file")" \
    'Tagged README DOI does not identify the retrieved Zenodo seed record'

  versions_url=$(jq -r '.links.versions // empty' "$seed_file")
  latest_url=$(jq -r '.links.latest // empty' "$seed_file")
  parent_url=$(jq -r '.links.parent // empty' "$seed_file")
  parent_doi_url=$(jq -r '.links.parent_doi // empty' "$seed_file")
  [[ "$versions_url" =~ ^https://zenodo\.org/api/records/[0-9]+/versions$ ]] ||
    fail "Zenodo seed record exposes an unsafe versions URL: $versions_url"
  [[ "$latest_url" =~ ^https://zenodo\.org/api/records/[0-9]+/versions/latest$ ]] ||
    fail "Zenodo seed record exposes an unsafe latest URL: $latest_url"
  [[ "$parent_url" =~ ^https://zenodo\.org/api/records/[0-9]+$ ]] ||
    fail "Zenodo seed record exposes an unsafe parent URL: $parent_url"
  [[ "$parent_doi_url" =~ ^https://doi\.org/10\.5281/zenodo\.[0-9]+$ ]] ||
    fail "Zenodo seed record exposes an unsafe parent DOI URL: $parent_doi_url"
  concept_doi=${parent_doi_url#https://doi.org/}

  : > "$TMP/zenodo-hits.ndjson"
  while (( page <= max_pages )); do
    page_file="$TMP/zenodo-pages/page-${page}.json"
    curl --fail --silent --show-error --location \
      --retry 5 --retry-delay 1 --retry-max-time 30 \
      -H 'Accept: application/json' \
      -H 'User-Agent: Regelsuche-published-release-audit/1' \
      --get "$versions_url" \
      --data-urlencode 'sort=mostrecent' \
      --data-urlencode "size=${page_size}" \
      --data-urlencode "page=${page}" \
      > "$page_file"

    jq -e '.hits.hits | type == "array"' "$page_file" >/dev/null ||
      fail "Zenodo versions page ${page} has no records array"
    page_count=$(jq '.hits.hits | length' "$page_file")
    if (( total < 0 )); then
      total=$(zenodo_total "$page_file")
      [[ "$total" =~ ^[0-9]+$ ]] ||
        fail "Zenodo versions endpoint returned an invalid total: $total"
      (( total <= page_size * max_pages )) ||
        fail "Zenodo concept exceeds the bounded ${page_size}x${max_pages} audit window"
    fi

    jq -c '.hits.hits[]' "$page_file" >> "$TMP/zenodo-hits.ndjson"
    seen=$((seen + page_count))
    if (( seen >= total )); then
      break
    fi
    (( page_count > 0 )) ||
      fail "Zenodo pagination ended before all ${total} versions were returned"
    page=$((page + 1))
  done

  (( seen == total )) ||
    fail "Zenodo pagination retained ${seen} of ${total} versions"
  if (( total == 0 )); then
    jq -n '{hits: {hits: [], total: 0}}' > "$target"
  else
    jq -s --argjson total "$total" \
      '{hits: {hits: ., total: $total}}' \
      "$TMP/zenodo-hits.ndjson" > "$target"
  fi
  jq -e '
    [.hits.hits[].id] as $ids
    | ($ids | length) == ($ids | unique | length)
  ' "$target" >/dev/null ||
    fail "Zenodo versions endpoint returned duplicate record IDs"

  curl --fail --silent --show-error --location \
    --retry 5 --retry-delay 1 --retry-max-time 30 \
    -H 'Accept: application/json' \
    -H 'User-Agent: Regelsuche-published-release-audit/1' \
    "$latest_url" > "$latest_file"

  jq -n \
    --arg seedDoi "$seed_doi" \
    --arg seedRecordId "$seed_record" \
    --arg conceptDoi "$concept_doi" \
    --arg versionsUrl "$versions_url" \
    --arg latestUrl "$latest_url" \
    --arg parentUrl "$parent_url" \
    '{
      seedDoi: $seedDoi,
      seedRecordId: $seedRecordId,
      conceptDoi: $conceptDoi,
      versionsUrl: $versionsUrl,
      latestUrl: $latestUrl,
      parentUrl: $parentUrl
    }' > "$metadata_target"
}

# Public GitHub release and exact asset membership.
gh api \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "repos/${REPOSITORY}/releases/tags/${TAG}" > "$TMP/release.json"

require_equal "$TAG" \
  "$(jq -r '.tag_name' "$TMP/release.json")" \
  'GitHub release tag mismatch'
require_equal "Regelsuche ${VERSION}" \
  "$(jq -r '.name' "$TMP/release.json")" \
  'GitHub release title mismatch'
require_equal false \
  "$(jq -r '.draft' "$TMP/release.json")" \
  'GitHub release is still a draft'
require_equal false \
  "$(jq -r '.prerelease' "$TMP/release.json")" \
  'GitHub release is unexpectedly a prerelease'

EXPECTED_ASSETS=$(printf '%s\n' \
  RELEASE-MANIFEST.txt \
  SHA256SUMS.txt \
  "regelsuche-${VERSION}.jar" \
  "regelsuche-${VERSION}.tar" \
  "regelsuche-${VERSION}.zip" | sort)
ACTUAL_ASSETS=$(jq -r '.assets[].name' "$TMP/release.json" | sort)
require_equal "$EXPECTED_ASSETS" "$ACTUAL_ASSETS" \
  'Published GitHub asset set differs from the release contract'

jq -e '
  [.assets[] |
    select(.state != "uploaded" or .size <= 0 or .size > 536870912)]
  | length == 0
' "$TMP/release.json" >/dev/null ||
  fail 'One or more GitHub assets are incomplete or outside the 512 MiB limit'

jq -e '
  [.assets[] |
    select(
      (((.digest // "") | test("^sha256:[0-9a-f]{64}$")) | not)
    )]
  | length == 0
' "$TMP/release.json" >/dev/null ||
  fail 'A GitHub release asset lacks a valid declared SHA-256 digest'

fetch_tagged_file \
  "docs/releases/${VERSION}.md" \
  "$TMP/tagged/release-notes.md"
jq -j -r '.body' "$TMP/release.json" > "$TMP/actual-release-notes.md"
if ! cmp -s \
    "$TMP/tagged/release-notes.md" \
    "$TMP/actual-release-notes.md"; then
  echo "Tagged notes sha256: $(sha256sum "$TMP/tagged/release-notes.md" | awk '{print $1}')" >&2
  echo "GitHub body sha256: $(sha256sum "$TMP/actual-release-notes.md" | awk '{print $1}')" >&2
  fail 'Published release body differs byte-for-byte from tagged curated notes'
fi

gh release download "$TAG" \
  --repo "$REPOSITORY" \
  --dir "$TMP/assets"
DOWNLOADED_ASSETS=$(
  for asset_path in "$TMP/assets"/*; do
    [[ -f "$asset_path" ]] || continue
    basename "$asset_path"
  done | sort
)
require_equal "$EXPECTED_ASSETS" "$DOWNLOADED_ASSETS" \
  'Downloaded asset set differs from the release contract'

while IFS=$'\t' read -r name expected_size expected_digest; do
  [[ -n "$name" ]] || continue
  actual_size=$(wc -c < "$TMP/assets/$name" | tr -d '[:space:]')
  require_equal "$expected_size" "$actual_size" \
    "Downloaded size mismatch for $name"
  actual_digest=$(sha256sum "$TMP/assets/$name" | awk '{print $1}')
  [[ "$expected_digest" =~ ^sha256:[0-9a-f]{64}$ ]] ||
    fail "GitHub asset digest is missing or malformed for $name"
  require_equal "sha256:${actual_digest}" "$expected_digest" \
    "GitHub digest mismatch for $name"
done < <(
  jq -r \
    '.assets[] | [.name, (.size|tostring), (.digest // "")] | @tsv' \
    "$TMP/release.json"
)

(
  cd "$TMP/assets"
  sha256sum --check --strict SHA256SUMS.txt
)

EXPECTED_CHECKSUM_ENTRIES=$(printf '%s\n' \
  RELEASE-MANIFEST.txt \
  "regelsuche-${VERSION}.jar" \
  "regelsuche-${VERSION}.tar" \
  "regelsuche-${VERSION}.zip" | sort)
ACTUAL_CHECKSUM_ENTRIES=$(awk '
  NF >= 2 {
    name=$2
    sub(/^\*/, "", name)
    print name
  }
' "$TMP/assets/SHA256SUMS.txt" | sort)
require_equal "$EXPECTED_CHECKSUM_ENTRIES" "$ACTUAL_CHECKSUM_ENTRIES" \
  'SHA256SUMS membership mismatch'

awk -F= '
  NF < 2 || $1 == "" { exit 2 }
  seen[$1]++ { exit 3 }
  {
    key=$1
    sub(/^[^=]*=/, "", $0)
    print key "\t" $0
  }
' "$TMP/assets/RELEASE-MANIFEST.txt" > "$TMP/manifest.tsv" ||
  fail 'Malformed or duplicate release manifest key'

EXPECTED_MANIFEST_KEYS=$(printf '%s\n' \
  build_system \
  openapi_sha256 \
  release_commit \
  release_notes_file \
  release_notes_sha256 \
  release_properties_sha256 \
  root_pom_sha256 \
  source_main_commit \
  tag \
  version | sort)
ACTUAL_MANIFEST_KEYS=$(cut -f1 "$TMP/manifest.tsv" | sort)
require_equal "$EXPECTED_MANIFEST_KEYS" "$ACTUAL_MANIFEST_KEYS" \
  'Release manifest key set differs from the contract'

manifest_value() {
  local key=$1
  awk -F'\t' -v wanted="$key" '
    $1 == wanted {
      print substr($0, index($0, "\t") + 1)
    }
  ' "$TMP/manifest.tsv"
}

require_equal "$VERSION" "$(manifest_value version)" \
  'Release manifest version mismatch'
require_equal "$TAG" "$(manifest_value tag)" \
  'Release manifest tag mismatch'
require_equal maven "$(manifest_value build_system)" \
  'Release manifest build-system mismatch'
require_equal "docs/releases/${VERSION}.md" \
  "$(manifest_value release_notes_file)" \
  'Release manifest notes path mismatch'

# Annotated tag, release commit, source parent and maintenance branch.
gh api \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "repos/${REPOSITORY}/git/ref/tags/${TAG}" > "$TMP/tag-ref.json"
require_equal tag \
  "$(jq -r '.object.type' "$TMP/tag-ref.json")" \
  'Release tag is lightweight instead of annotated'
TAG_OBJECT=$(jq -r '.object.sha' "$TMP/tag-ref.json")
gh api \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "repos/${REPOSITORY}/git/tags/${TAG_OBJECT}" > "$TMP/tag-object.json"
require_equal "$TAG" \
  "$(jq -r '.tag' "$TMP/tag-object.json")" \
  'Annotated tag name mismatch'
require_equal commit \
  "$(jq -r '.object.type' "$TMP/tag-object.json")" \
  'Annotated tag does not target a commit'
RELEASE_COMMIT=$(jq -r '.object.sha' "$TMP/tag-object.json")
require_equal "$RELEASE_COMMIT" "$(manifest_value release_commit)" \
  'Manifest release commit differs from annotated tag'

gh api \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "repos/${REPOSITORY}/commits/${RELEASE_COMMIT}" \
  > "$TMP/release-commit.json"
require_equal 1 \
  "$(jq '.parents | length' "$TMP/release-commit.json")" \
  'Release metadata commit must have exactly one parent'
SOURCE_COMMIT=$(jq -r '.parents[0].sha' "$TMP/release-commit.json")
require_equal "$SOURCE_COMMIT" "$(manifest_value source_main_commit)" \
  'Manifest source commit differs from release parent'

MAINTENANCE_COMMIT=$(gh api \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "repos/${REPOSITORY}/git/ref/heads/maintenance/${SERIES}.x" \
  --jq '.object.sha')
require_equal "$RELEASE_COMMIT" "$MAINTENANCE_COMMIT" \
  "maintenance/${SERIES}.x does not point to the tagged release commit"

# Tagged bytes must match every hash bound by RELEASE-MANIFEST.txt.
fetch_tagged_file release.properties "$TMP/tagged/release.properties"
fetch_tagged_file \
  app/src/main/resources/web/openapi/openapi.json \
  "$TMP/tagged/openapi.json"
fetch_tagged_file pom.xml "$TMP/tagged/pom.xml"
fetch_tagged_file .zenodo.json "$TMP/tagged/zenodo.json"
fetch_tagged_file README.md "$TMP/tagged/README.md"

require_equal "$(manifest_value release_notes_sha256)" \
  "$(sha256sum "$TMP/tagged/release-notes.md" | awk '{print $1}')" \
  'Tagged release notes hash differs from manifest'
require_equal "$(manifest_value release_properties_sha256)" \
  "$(sha256sum "$TMP/tagged/release.properties" | awk '{print $1}')" \
  'Tagged release.properties hash differs from manifest'
require_equal "$(manifest_value openapi_sha256)" \
  "$(sha256sum "$TMP/tagged/openapi.json" | awk '{print $1}')" \
  'Tagged OpenAPI hash differs from manifest'
require_equal "$(manifest_value root_pom_sha256)" \
  "$(sha256sum "$TMP/tagged/pom.xml" | awk '{print $1}')" \
  'Tagged root POM hash differs from manifest'
require_equal "version=${VERSION}" \
  "$(sed -n 's/^version=.*/&/p' "$TMP/tagged/release.properties")" \
  'Tagged release.properties version mismatch'
require_equal "$VERSION" \
  "$(jq -r '.info.version' "$TMP/tagged/openapi.json")" \
  'Tagged OpenAPI version mismatch'
grep -Fq "<version>${VERSION}</version>" "$TMP/tagged/pom.xml" ||
  fail 'Tagged root POM version mismatch'
require_equal "$VERSION" \
  "$(jq -r '.version' "$TMP/tagged/zenodo.json")" \
  'Tagged Zenodo metadata version mismatch'
require_equal Regelsuche \
  "$(jq -r '.title' "$TMP/tagged/zenodo.json")" \
  'Tagged Zenodo title mismatch'

# JAR, archive roots and packaged web asset.
JAR="$TMP/assets/regelsuche-${VERSION}.jar"
ZIP="$TMP/assets/regelsuche-${VERSION}.zip"
TAR="$TMP/assets/regelsuche-${VERSION}.tar"
unzip -p "$JAR" META-INF/MANIFEST.MF |
  tr -d '\r' > "$TMP/jar-manifest.txt"
grep -Fqx "Implementation-Version: ${VERSION}" "$TMP/jar-manifest.txt" ||
  fail 'Application JAR Implementation-Version mismatch'
unzip -p "$JAR" \
  web/vendor/cytoscape/cytoscape.min.js > "$TMP/cytoscape.min.js"
[[ $(wc -c < "$TMP/cytoscape.min.js") -gt 100000 ]] ||
  fail 'Packaged Cytoscape asset is unexpectedly small'
grep -Fq 'version="3.34.1"' "$TMP/cytoscape.min.js" ||
  fail 'Packaged Cytoscape revision differs from 3.34.1'
validate_archive zip "$ZIP" "$TMP/zip-entries.txt"
validate_archive tar "$TAR" "$TMP/tar-entries.txt"
extract_zip_safely "$ZIP" "$TMP/distribution"
DIST="$TMP/distribution/regelsuche-${VERSION}"
[[ -s "$DIST/regelsuche.jar" ]] ||
  fail 'ZIP distribution lacks regelsuche.jar'
[[ -d "$DIST/lib" ]] ||
  fail 'ZIP distribution lacks runtime libraries'

# Two bounded product paths from the downloaded distribution.
CLASSPATH="$DIST/regelsuche.jar:$DIST/lib/*"
SYSTEM_OUTPUT=$(run_smoke exact-system \
  java -cp "$CLASSPATH" de.regelsuche.App system \
  '2*x + y = 5; x - y = 1')
grep -Fq 'Recognized exact matrix representation' "$SYSTEM_OUTPUT" ||
  fail 'Released product did not recognize the exact A*x=b representation'
grep -Fq 'RREF(A|b) = [[1, 0 | 2], [0, 1 | 1]]' "$SYSTEM_OUTPUT" ||
  fail 'Released product returned an unexpected exact RREF'
grep -Fq 'Exact solution: [x=2, y=1]' "$SYSTEM_OUTPUT" ||
  fail 'Released product returned an unexpected exact solution'

cat > "$TMP/PolynomialReleaseSmoke.java" <<'JAVA'
import de.regelsuche.transform.PolynomialDecompositionSynthesisOperator;
import java.math.BigInteger;
import java.util.List;

public class PolynomialReleaseSmoke {
    private static final List<BigInteger> TARGET = List.of(
        BigInteger.ONE,
        BigInteger.ZERO,
        BigInteger.ZERO,
        BigInteger.ZERO,
        BigInteger.valueOf(4));

    public static void main(String[] args) {
        var report = new PolynomialDecompositionSynthesisOperator()
            .synthesize("x^4 + 4*y^4");
        if (!report.generated()) {
            throw new IllegalStateException(report.detailCode());
        }
        boolean certified = report.candidates().stream()
            .anyMatch(PolynomialReleaseSmoke::reconstructsTarget);
        if (!certified) {
            throw new IllegalStateException(
                "no candidate factors reconstruct x^4 + 4*y^4");
        }
        System.out.println("POLYNOMIAL_RELEASE_SMOKE_OK");
    }

    private static boolean reconstructsTarget(
            PolynomialDecompositionSynthesisOperator.Candidate candidate) {
        List<BigInteger> left = candidate.leftCoefficients();
        List<BigInteger> right = candidate.rightCoefficients();
        List<BigInteger> reconstructed = List.of(
            left.get(0).multiply(right.get(0)),
            left.get(0).multiply(right.get(1))
                .add(left.get(1).multiply(right.get(0))),
            left.get(0).multiply(right.get(2))
                .add(left.get(1).multiply(right.get(1)))
                .add(left.get(2).multiply(right.get(0))),
            left.get(1).multiply(right.get(2))
                .add(left.get(2).multiply(right.get(1))),
            left.get(2).multiply(right.get(2)));
        return TARGET.equals(reconstructed)
            && candidate.certificateHash()
                .matches("sha256:[0-9a-f]{64}");
    }
}
JAVA
POLYNOMIAL_OUTPUT=$(run_smoke polynomial-synthesis \
  java --class-path "$CLASSPATH" "$TMP/PolynomialReleaseSmoke.java")
grep -Fq 'POLYNOMIAL_RELEASE_SMOKE_OK' "$POLYNOMIAL_OUTPUT" ||
  fail 'Released product did not synthesize a certified quartic decomposition'

# Zenodo concept/version audit. The tagged README DOI is a seed: historical
# releases may contain either a version DOI or the concept DOI. The retrieved
# record itself supplies the authoritative versions, latest and parent-DOI URLs.
ZENODO_SEED_DOI=$(grep -oE \
  '10\.5281/zenodo\.[0-9]+' \
  "$TMP/tagged/README.md" | head -n 1)
[[ -n "$ZENODO_SEED_DOI" ]] ||
  fail 'Tagged README does not expose a Zenodo DOI seed'
fetch_zenodo_version_lineage \
  "$ZENODO_SEED_DOI" \
  "$TMP/zenodo-versions.json" \
  "$TMP/zenodo-lineage.json"
jq -e '.hits.hits | type == "array" and length > 0' \
  "$TMP/zenodo-versions.json" >/dev/null ||
  fail "Zenodo exposes no versions for seed ${ZENODO_SEED_DOI}"

ZENODO_CONCEPT_DOI=$(jq -r '.conceptDoi' "$TMP/zenodo-lineage.json")
AVAILABLE_ZENODO_VERSIONS=$(jq -r '
  [.hits.hits[].metadata.version // empty]
  | unique
  | sort
  | join(",")
' "$TMP/zenodo-versions.json")
ZENODO_MATCH_COUNT=$(jq --arg version "$VERSION" '
  [.hits.hits[] |
    select(
      .metadata.version == $version
      and .metadata.title == "Regelsuche"
    )]
  | length
' "$TMP/zenodo-versions.json")
if [[ "$ZENODO_MATCH_COUNT" != 1 ]]; then
  fail "Zenodo concept ${ZENODO_CONCEPT_DOI} does not contain exactly one " \
    "Regelsuche ${VERSION} record (found ${ZENODO_MATCH_COUNT}; " \
    "published versions: ${AVAILABLE_ZENODO_VERSIONS:-<none>})"
fi
ZENODO_RECORD_ID=$(jq -r --arg version "$VERSION" '
  .hits.hits[]
  | select(
      .metadata.version == $version
      and .metadata.title == "Regelsuche"
    )
  | .id
' "$TMP/zenodo-versions.json")
LATEST_ZENODO_ID=$(jq -r '.id' "$TMP/zenodo-latest.json")
LATEST_ZENODO_VERSION=$(jq -r '.metadata.version' "$TMP/zenodo-latest.json")
require_equal "$VERSION" "$LATEST_ZENODO_VERSION" \
  'Zenodo concept does not expose the released version as latest'
require_equal "$ZENODO_RECORD_ID" "$LATEST_ZENODO_ID" \
  'Zenodo release record is not the concept latest version'

OTHER_ZENODO_VERSIONS=$(jq -r --arg version "$VERSION" '
  .hits.hits[].metadata.version // empty
  | select(. != $version)
' "$TMP/zenodo-versions.json" | sort -u)
[[ -n "$OTHER_ZENODO_VERSIONS" ]] ||
  fail 'Zenodo concept does not retain an earlier release version'

curl --fail --silent --show-error --location \
  --retry 5 --retry-delay 1 --retry-max-time 30 \
  -H 'Accept: application/json' \
  -H 'User-Agent: Regelsuche-published-release-audit/1' \
  "https://zenodo.org/api/records/${ZENODO_RECORD_ID}" \
  > "$TMP/zenodo-record.json"
ZENODO_VERSION=$(jq -r '.metadata.version' "$TMP/zenodo-record.json")
ZENODO_TITLE=$(jq -r '.metadata.title' "$TMP/zenodo-record.json")
ZENODO_PUBLICATION_DATE=$(
  jq -r '.metadata.publication_date' "$TMP/zenodo-record.json"
)
EXPECTED_PUBLICATION_DATE=$(
  jq -r '.publication_date' "$TMP/tagged/zenodo.json"
)
require_equal "$VERSION" "$ZENODO_VERSION" \
  'Zenodo version differs from tagged metadata'
require_equal Regelsuche "$ZENODO_TITLE" \
  'Zenodo title differs from tagged metadata'
require_equal "$EXPECTED_PUBLICATION_DATE" "$ZENODO_PUBLICATION_DATE" \
  'Zenodo publication date differs from tagged metadata'

EXPECTED_CREATORS=$(jq -r '
  .creators[]
  | [
      .name,
      (
        (.orcid // "")
        | sub("^https?://orcid.org/"; "")
      )
    ]
  | @tsv
' "$TMP/tagged/zenodo.json" | sort)
ACTUAL_CREATORS=$(jq -r '
  .metadata.creators[]
  | [
      (.name // .person_or_org.name // ""),
      (
        (
          .orcid
          // (
            [
              .person_or_org.identifiers[]?
              | select(
                  ((.scheme // "") | ascii_downcase) == "orcid"
                )
              | .identifier
            ][0]
          )
          // (
            [
              .identifiers[]?
              | select(
                  ((.scheme // "") | ascii_downcase) == "orcid"
                )
              | .identifier
            ][0]
          )
          // ""
        )
        | sub("^https?://orcid.org/"; "")
      )
    ]
  | @tsv
' "$TMP/zenodo-record.json" | sort)
require_equal "$EXPECTED_CREATORS" "$ACTUAL_CREATORS" \
  'Zenodo creators/ORCIDs differ from tagged metadata'

RECORD_CONCEPT_DOI=$(jq -r '
  .conceptdoi
  // .parent.pids.doi.identifier
  // (
    .links.parent_doi
    | sub("^https://doi.org/"; "")
  )
  // empty
' "$TMP/zenodo-record.json")
ZENODO_DOI=$(jq -r '
  .doi
  // .pids.doi.identifier
  // empty
' "$TMP/zenodo-record.json")
require_equal "$ZENODO_CONCEPT_DOI" "$RECORD_CONCEPT_DOI" \
  'Zenodo record is not bound to the authoritative parent concept DOI'
[[ -n "$ZENODO_DOI" ]] ||
  fail 'Zenodo version DOI is missing'

jq -r '
  if (.files | type) == "array" then
    .files[] | (.key // .filename // empty)
  elif (.files.entries | type) == "object" then
    .files.entries | keys[]
  else
    empty
  end
' "$TMP/zenodo-record.json" |
  sort -u > "$TMP/zenodo-files.txt"
[[ -s "$TMP/zenodo-files.txt" ]] ||
  fail 'Zenodo release contains no archived software file'
if ! grep -Ei \
    "regelsuche.*v?${VERSION//./\\.}|v?${VERSION//./\\.}.*regelsuche" \
    "$TMP/zenodo-files.txt" >/dev/null; then
  cat "$TMP/zenodo-files.txt" >&2
  fail 'Zenodo files do not identify the released Regelsuche version'
fi
while IFS= read -r filename; do
  while IFS= read -r embedded_version; do
    [[ -z "$embedded_version" || "$embedded_version" == "$VERSION" ]] ||
      fail "Zenodo file contains stale version ${embedded_version}: ${filename}"
  done < <(
    grep -oE '[0-9]+\.[0-9]+\.[0-9]+' <<< "$filename" || true
  )
done < "$TMP/zenodo-files.txt"

# Canonical machine-readable report retained by normal CI.
jq -Rn '
  [inputs |
    select(length > 0) |
    split("\t") |
    {(.[0]): .[1]}]
  | add
' < "$TMP/manifest.tsv" |
  jq -S . > "$TMP/manifest.json"

jq -n '{}' > "$TMP/assets.json"
while IFS=$'\t' read -r name size github_digest; do
  sha=$(sha256sum "$TMP/assets/$name" | awk '{print $1}')
  jq \
    --arg name "$name" \
    --argjson bytes "$size" \
    --arg sha "$sha" \
    --arg githubDigest "$github_digest" \
    '. + {
      ($name): (
        {bytes: $bytes, sha256: $sha}
        + {githubDigest: $githubDigest}
      )
    }' \
    "$TMP/assets.json" > "$TMP/assets.next.json"
  mv "$TMP/assets.next.json" "$TMP/assets.json"
done < <(
  jq -r \
    '.assets[] | [.name, (.size|tostring), (.digest // "")] | @tsv' \
    "$TMP/release.json"
)
jq -S . "$TMP/assets.json" > "$TMP/assets.sorted.json"

ZENODO_VERSIONS=$(
  printf '%s\n%s\n' "$VERSION" "$OTHER_ZENODO_VERSIONS" |
    sort -u |
    paste -sd, -
)
jq -n \
  --arg schema 'regelsuche.published-release-audit/v1' \
  --arg repository "$REPOSITORY" \
  --arg version "$VERSION" \
  --arg tag "$TAG" \
  --arg githubReleaseId "$(jq -r '.id' "$TMP/release.json")" \
  --arg githubReleaseUrl "$(jq -r '.html_url' "$TMP/release.json")" \
  --arg annotatedTagObject "$TAG_OBJECT" \
  --arg releaseCommit "$RELEASE_COMMIT" \
  --arg sourceMainCommit "$SOURCE_COMMIT" \
  --arg maintenanceCommit "$MAINTENANCE_COMMIT" \
  --arg archiveRoot "${EXPECTED_ROOT%/}" \
  --arg cytoscapeVersion '3.34.1' \
  --arg systemSmoke 'EXACT_SYSTEM_RREF_OK' \
  --arg polynomialSmoke 'POLYNOMIAL_DECOMPOSITION_SYNTHESIS_OK' \
  --arg zenodoRecordId "$ZENODO_RECORD_ID" \
  --arg zenodoDoi "$ZENODO_DOI" \
  --arg zenodoSeedDoi "$ZENODO_SEED_DOI" \
  --arg zenodoConceptDoi "$ZENODO_CONCEPT_DOI" \
  --arg zenodoVersions "$ZENODO_VERSIONS" \
  --slurpfile assets "$TMP/assets.sorted.json" \
  --slurpfile manifest "$TMP/manifest.json" \
  --rawfile zenodoFiles "$TMP/zenodo-files.txt" \
  '{
    schema: $schema,
    repository: $repository,
    version: $version,
    tag: $tag,
    githubReleaseId: $githubReleaseId,
    githubReleaseUrl: $githubReleaseUrl,
    annotatedTagObject: $annotatedTagObject,
    releaseCommit: $releaseCommit,
    sourceMainCommit: $sourceMainCommit,
    maintenanceCommit: $maintenanceCommit,
    archiveRoot: $archiveRoot,
    cytoscapeVersion: $cytoscapeVersion,
    systemSmoke: $systemSmoke,
    polynomialSmoke: $polynomialSmoke,
    assets: $assets[0],
    manifest: $manifest[0],
    zenodo: {
      seedDoi: $zenodoSeedDoi,
      recordId: $zenodoRecordId,
      doi: $zenodoDoi,
      conceptDoi: $zenodoConceptDoi,
      files: (
        $zenodoFiles
        | split("\n")
        | map(select(length > 0))
        | sort
      ),
      versions: (
        $zenodoVersions
        | split(",")
        | map(select(length > 0))
        | sort
      )
    }
  }' |
  jq -S . > "$REPORT"

echo "Published release audit passed: $REPORT"
