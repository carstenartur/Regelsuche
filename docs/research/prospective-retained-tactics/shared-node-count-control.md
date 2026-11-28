# Supplementary primitive node-count control

This technical comparison was specified after the ten-arm protocol and before generation of the two new panels. It estimates how much of a gain over ordinary primitive search can be explained by avoiding repeated evaluations of the native node-count objective. The registered primary comparison and its success criteria still use the ten original arms.

The two arms use exactly the ordinary primitive inventory, scheduler, verifier, exact polynomial audit and independent terminal replay. They each have zero training, model-loading and solver preparation charges. BASE calls `solve(task)`. SHARED calls `solveWithSharedNodeCounts(task)`. The latter retains objective values by immutable expression identity only for the duration of the query. It charges the native node visits, cache creation, lookup, insertion and eviction. Both arms use the original per-case and cumulative budgets. In either arm, a shorter returned partial expression still needs a checked proof and consumes its measured work.

The three public development streams are MIXED, A and B. For C and D the runner reads the sources, node goals and budgets directly from a completed official rotation. It requires the registered freeze identity and compares every reconstructed BASE record with its official counterpart. The technical runner does not generate the held-out panels.

Run with Java 25 and Maven from the repository root. Output files must be new. Set `seed` to the complete official freeze SHA and `official` to the absolute path of an official rotation containing the C and D result directories.

```bash
runs=$(mktemp -d)
for r in 0 1; do
  mvn -B -pl regelsuche-learning -am -Dtest=SharedNodeCountStudyTest \
    -Dsurefire.failIfNoSpecifiedTests=false "-Dshared.counts.output=$runs/counts-$r.json" \
    "-Dshared.counts.rotation=$r" "-Dretained.freeze=$seed" "-Dshared.counts.official=$official" test || exit 1
done
cmp "$runs/counts-0.json" "$runs/counts-1.json"
```

Omit `shared.counts.official` to run only the three published development streams. Each report includes solutions by deadline, total solutions, the complete work bill, its components, the number of distinct proof paths and a SHA-256 digest covering every result row and its proof reference. The second JVM reverses arm order. The Java regression suite is `SharedNodeCountTest`.
