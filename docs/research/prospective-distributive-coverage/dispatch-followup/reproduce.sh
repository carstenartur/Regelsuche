#!/usr/bin/env bash
set -euo pipefail
repo=$(git rev-parse --show-toplevel)
follow="$repo/docs/research/prospective-distributive-coverage/dispatch-followup"
base=${REG_DISPATCH_BASE:-dc39f6e0199d3f38fd8c82ac9a35230a83b0c66e}
test "$(git -C "$repo" rev-parse "$base^{tree}")" = a7f238b6655af31b1f584a2e878a18a4ba31a485
run=$(mktemp -d)
mkdir "$run/source"
git -C "$repo" archive "$base" | tar -x -C "$run/source"
cd "$run/source"
study="$PWD/docs/research/prospective-distributive-coverage"
freeze=sha256:6863b403ed13292dd4aefde8603ba83b1f7ff221ee5dab091a7f019108947930
replay() {
    mvn -B -pl regelsuche-learning -am -Dtest=DistributiveCoverageStudyTest \
        -Dsurefire.failIfNoSpecifiedTests=false -Ddistributive.phase=final \
        -Ddistributive.directory="$study" -Ddistributive.digest="$freeze" \
        -Ddistributive.rotation="$1" -Ddistributive.output="$2" test
}
git apply "$follow/diagnostic.patch"
replay 0 "$run/diagnostic" > "$run/diagnostic.log"
git apply -R "$follow/diagnostic.patch"
git apply "$follow/grandchild-filter.patch"
replay 0 "$run/filter-0"
replay 1 "$run/filter-1"
python3 "$study/compare.py" "$run/filter-0" "$run/filter-1" "$run/compared"
python3 "$follow/pairs.py" "$study/results" "$run/diagnostic" "$run/diagnostic.log" "$run/filter-0" "$run/paired"
cmp "$follow/learned-pairs.csv" "$run/paired/learned-pairs.csv"
cmp "$follow/reference-dispatch.csv" "$run/paired/reference-dispatch.csv"
echo "Verified both ledgers. Raw results: $run"
