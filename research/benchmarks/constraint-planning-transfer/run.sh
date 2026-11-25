#!/usr/bin/env bash
set -euo pipefail
study_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$study_dir/../../.." && pwd)
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
if [[ -n ${REGELSUCHE_STUDY_CLASSPATH:-} ]]; then
    classpath=$REGELSUCHE_STUDY_CLASSPATH
else
    cd "$repo_root"
    mvn -q -pl regelsuche-math-algorithms -am -Dmaven.test.skip=true install >&2
    mvn -q -pl regelsuche-math-algorithms dependency:build-classpath "-Dmdep.outputFile=$build_dir/classpath" >&2
    classpath="$repo_root/regelsuche-math-algorithms/target/classes:$(cat "$build_dir/classpath")"
fi
javac -Xlint:all -Werror -cp "$classpath" -d "$build_dir" "$study_dir/ConstraintPlanningStudy.java" >&2
java -XX:-UsePerfData -Xmx2g -cp "$build_dir:$classpath" ConstraintPlanningStudy "$study_dir"
