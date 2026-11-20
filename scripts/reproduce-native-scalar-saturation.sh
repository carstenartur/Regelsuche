#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || ( "$2" != "--host-only" && "$2" != "--with-container" ) ]]; then
    echo "Usage: $0 <new evidence directory> --host-only|--with-container [Gradle options]" >&2
    exit 2
fi
output=$(realpath -m "$1")
mode=$2
shift 2
root=$(git rev-parse --show-toplevel)
revision=$(git -C "$root" rev-parse --verify HEAD)
mkdir -p "$(dirname "$output")"
mkdir "$output"
source_area=$(mktemp -d)
trap 'rm -rf "$source_area"' EXIT
for name in first second; do
    checkout="$source_area/$name"
    mkdir "$checkout"
    git -C "$root" archive --format=tar "$revision" | tar -xf - -C "$checkout"
    (
        cd "$checkout"
        REGELSUCHE_PREPARATION_REVISION="$revision" ./gradlew --no-daemon --no-configuration-cache --max-workers=2 "$@" :regelsuche-benchmarks:installDist
        ./regelsuche-benchmarks/build/install/regelsuche-benchmarks/bin/regelsuche-benchmarks --native-scalar-comparison \
            scripts/sympy_rule_reference/reference.py "$revision" "$output/$name"
    ) > "$output/$name.log" 2>&1
done
diff -qr "$output/first" "$output/second"
printf '%s\n' "$revision" > "$output/source-revision.txt"
(cd "$output/first" && find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$output/checksums.txt"
if [[ "$mode" = "--with-container" ]]; then
    image_name="regelsuche-native-scalar:${revision:0:16}"
    docker build -f "$source_area/first/Dockerfile.comparative-benchmarks" --build-arg "REGELSUCHE_PREPARATION_REVISION=$revision" \
        -t "$image_name" "$source_area/first" > "$output/container-build.log" 2>&1
    image_id=$(docker image inspect --format '{{.Id}}' "$image_name")
    [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]
    printf '%s\n' "$image_id" > "$output/container-image-id.txt"
    mkdir "$output/container"
    docker run --rm --network none --user "$(id -u):$(id -g)" -v "$output/container:/output" "$image_id" \
        --native-scalar-comparison /opt/regelsuche-sympy-reference/reference.py "$revision" /output > "$output/container-run.log" 2>&1
    diff -qr "$output/first" "$output/container"
fi
printf 'source=%s scope=%s canonical-files=%s\n' "$revision" "$mode" "$(wc -l < "$output/checksums.txt")"
