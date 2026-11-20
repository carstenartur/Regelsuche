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
        # Reproduce through the Maven reactor, not an additional Gradle host driver.
        # The runtime classpath is generated from this clean, unpacked source tree.
        REGELSUCHE_PREPARATION_REVISION="$revision" mvn -B -ntp -pl regelsuche-benchmarks -am \
            -DskipTests package \
            org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
            -DincludeScope=runtime -Dmdep.outputFile=target/native-scalar-runtime-classpath.txt
        runtime_classpath=$(cat regelsuche-benchmarks/target/native-scalar-runtime-classpath.txt)
        [[ -n "$runtime_classpath" ]]
        REGELSUCHE_PREPARATION_REVISION="$revision" java \
            -cp "regelsuche-benchmarks/target/classes:$runtime_classpath" \
            de.regelsuche.benchmarks.ComparativeBenchmarkMain --native-scalar-comparison \
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
