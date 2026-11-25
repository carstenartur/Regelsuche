#!/usr/bin/env bash
set -euo pipefail
bench_dir=$(cd -- "$(dirname -- "$0")" && pwd)
old_dir="$bench_dir/../bilinear-form-transfer"
out_dir=$(mktemp -d)
trap 'rm -rf -- "$out_dir"' EXIT
result_dir=${1:?Usage: run.sh OUTPUT_DIR}
mkdir -p -- "$result_dir"
if [[ -n ${BILINEAR_BENCH_CLASSPATH:-} ]]; then
    classpath=$BILINEAR_BENCH_CLASSPATH
else
    mvn -q -f "$old_dir/pom.xml" dependency:build-classpath "-Dmdep.outputFile=$out_dir/cp"
    classpath=$(cat "$out_dir/cp")
fi
javac -Xlint:all -Werror -proc:none -cp "$classpath" -d "$out_dir" "$bench_dir/GenerateSelection.java" "$old_dir/GenerateTrial.java"
java -cp "$out_dir:$classpath" transfer.GenerateTrial "$out_dir/GeneratedKernels.java" > "$result_dir/first-fit.txt"
(cd "$out_dir" && sha256sum -c "$bench_dir/first-fit.sha256")
java -cp "$out_dir:$classpath" transfer.GenerateSelection "$out_dir/Selected.java" > "$result_dir/selection.txt"
cp "$out_dir/Selected.java" "$result_dir/selected-source.txt"
javac -Xlint:all -Werror -processor org.openjdk.jmh.generators.BenchmarkProcessor -cp "$classpath" -d "$out_dir" \
    "$bench_dir/SelectionBench.java" "$out_dir/Selected.java" "$out_dir/GeneratedKernels.java"
java -cp "$out_dir:$classpath" org.openjdk.jmh.Main '^transfer[.]SelectionBench[.]' \
    -foe true -jvmArgs '-Xms128m -Xmx256m' -prof gc -rf json -rff "$result_dir/results.json"
