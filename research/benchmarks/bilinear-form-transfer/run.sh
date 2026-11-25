#!/usr/bin/env bash
set -euo pipefail
bench_dir=$(cd -- "$(dirname -- "$0")" && pwd)
out_dir=$(mktemp -d)
trap 'rm -rf -- "$out_dir"' EXIT
if [[ -n ${BILINEAR_BENCH_CLASSPATH:-} ]]; then
    classpath=$BILINEAR_BENCH_CLASSPATH
else
    mvn -q -f "$bench_dir/pom.xml" dependency:build-classpath "-Dmdep.outputFile=$out_dir/cp"
    classpath=$(cat "$out_dir/cp")
fi
javac -Xlint:all -Werror -proc:none -cp "$classpath" -d "$out_dir" "$bench_dir/GenerateTrial.java"
java -cp "$out_dir:$classpath" transfer.GenerateTrial "$out_dir/GeneratedKernels.java"
javac -Xlint:all -Werror -cp "$classpath" -processor org.openjdk.jmh.generators.BenchmarkProcessor \
    -d "$out_dir" "$bench_dir/BilinearBench.java" "$out_dir/GeneratedKernels.java"
java -cp "$out_dir:$classpath" org.openjdk.jmh.Main '^transfer[.]BilinearBench[.]' \
    -foe true -jvmArgs '-Xms128m -Xmx256m' "$@"
