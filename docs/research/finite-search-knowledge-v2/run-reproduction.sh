#!/usr/bin/env bash
set -euo pipefail
# Run in a fresh checkout of the frozen producer, using the documented production classpath.
case "${1:?build or measure}" in
  build)
    maven="${2:?absolute Maven executable}"
    dependencies="${3:?absolute Maven repository}"
    "$maven" --version
    "$maven" -B -ntp "-Dmaven.repo.local=$dependencies" -pl regelsuche-experiments -am \
      -DskipTests -DincludeScope=runtime -Dmdep.outputFile=target/finite-search-classpath.txt \
      package dependency:build-classpath
    ;;
  measure)
    output="${2:?absolute evidence directory}"
    test ! -e "$output/comparison.json"
    test ! -e "$output/runtime.json"
    java -version
    module=regelsuche-experiments/target
    exec java -Xmx512m -cp "$module/classes:$(cat "$module/finite-search-classpath.txt")" \
      de.regelsuche.benchmark.FiniteSearchKnowledgeComparison "$output/comparison.json" "$output/runtime.json"
    ;;
  *) exit 2 ;;
esac
