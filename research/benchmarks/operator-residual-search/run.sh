#!/usr/bin/env bash
set -euo pipefail
bench_dir=$(cd -- "$(dirname -- "$0")" && pwd)
result_dir=${1:?Usage: run.sh OUTPUT_DIR}
mkdir -p -- "$result_dir"
compile_dir=$(mktemp -d)
trap 'rm -rf -- "$compile_dir"' EXIT
if [[ -n ${OPERATOR_BENCH_CLASSPATH:-} ]]; then
    classpath=$OPERATOR_BENCH_CLASSPATH
else
    mvn -q -f "$bench_dir/pom.xml" dependency:build-classpath "-Dmdep.outputFile=$compile_dir/classpath"
    classpath=$(cat "$compile_dir/classpath")
fi
javac -Xlint:all -Werror -proc:none \
    -cp "$classpath" -d "$compile_dir" "$bench_dir/OperatorResidualStudy.java"
java -cp "$compile_dir:$classpath" operatorstudy.OperatorResidualStudy selection > "$result_dir/selection.tsv"
for fork in 1 2 3; do
    for family in center8 center16 diagonal8 dense8; do
        for arm in acquire selected directChecked directArithmetic specializedArithmetic; do
            java -Xms128m -Xmx256m -cp "$compile_dir:$classpath" operatorstudy.OperatorResidualStudy \
                measure "$family" "$arm" > "$result_dir/$family-$arm-$fork.tsv"
        done
    done
done
