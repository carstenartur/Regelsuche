# Root-shape discrimination: development benchmark

This optional experiment accompanies #696. It compares the existing prepared
executor with `withRuleIndex()` on the **same ordered four-successor relation**.
It measures local execution time, not search capability, canonical work saved,
or a held-out scientific result. No default executor or existing CI threshold
is changed.

See [both retained runs, including losses](results/2026-09-19/RESULTS.md) and
their raw JMH data. The large sparse fixture benefits strongly; the small and
same-operator controls show why a universal speedup claim would be unsupported.

## Scope

The index checks necessary root conditions of ordinary `PatternRewriteRule`
instances: operator, function name/arity, exact number and literal variable.
Wildcards, subclasses, custom compiled genome rules and recognition profiles
that may cross shapes remain in an ordered fallback list. The matcher still
decides applicability. Storage is linear in inventory size; fallback entries
are not duplicated into every bucket. Occurrence traversal, duplicate rule
entries, bounds, assumptions, proof identities and successor order are retained.

This first slice does not index native cursors or accelerate arbitrary learned
rules. Those require separately qualified structural contracts. It also does
not reduce the current work ledger: that ledger does not count matcher attempts.

## Protocol

- JMH 1.37; OpenJDK 25.0.2; Linux x86-64, Intel Xeon Platinum 8370C, 9 visible CPUs.
- Average time in microseconds, one thread, two JVM forks, 256 MiB initial and
  maximum heap; three warm-up and five measurement iterations per fork.
- All 18 combinations are retained: 16/256/4096 unrelated rules, two inventory
  shapes, and scan/indexed/index-construction operations.
- Source: `context(x0+0,x1+0,x2+0,x3+0)`, 13 AST occurrences, four useful rewrites.
- `SPARSE_FUNCTIONS`: unrelated function heads; `SAME_OPERATOR`: ADD roots with
  incompatible positive constants. A final rule removes zero. Extra rules are
  exact identities, not unsupported mathematical proposals.
- Setup checks exact ordered successor equality and count before every fork.
- `compileIndex` measures `scanEngine.withRuleIndex()` separately from queries;
  it does not include an additional call to `astTransport()` or inventory creation.
- Pilot: 500 ms iterations; some early measurements overlapped local builds.
  Repeat: 1 s iterations, chosen before looking at its results, with no concurrent
  task-owned builds. Both runs are retained; this is still a shared development
  host, not a controlled or pinned benchmark machine.
- No profile is selected or excluded according to outcome. Point estimates,
  JMH errors and raw fork measurements are retained, including small-inventory
  regressions and uncertainty. No existing benchmark inventory, baseline or gate
  is replaced.

For the sparse 4096-rule fixture, a full scan admits 53,261 rule/occurrence pairs
to matching; root filtering admits four. This is a structural admission count,
not a runtime ratio or a newly credited search-work reduction.

## Reproduction

Compile the core on Java 25 with `mvn -pl regelsuche-core -am compile`.
Use the following dependency jars, already used by the repository's JMH setup:
`jmh-core:1.37`, `jopt-simple:5.0.4`, `commons-math3:3.6.1` and Jackson annotations.
Set `JMH_RUNTIME_CP` to their platform-separated jar paths and
`JMH_PROCESSOR_CP` to `jmh-generator-annprocess:1.37` plus `jmh-core:1.37`.
From the repository root on Linux:

```sh
mkdir -p build/rule-shape-index-local/classes
"$JAVA_HOME/bin/javac" \
  -cp "regelsuche-core/target/classes:$JMH_RUNTIME_CP" \
  -processorpath "$JMH_PROCESSOR_CP" \
  -d build/rule-shape-index-local/classes \
  research/benchmarks/rule-shape-index/RuleShapeIndexBenchmarks.java
"$JAVA_HOME/bin/java" \
  -cp "build/rule-shape-index-local/classes:regelsuche-core/target/classes:$JMH_RUNTIME_CP" \
  org.openjdk.jmh.Main de.regelsuche.research.RuleShapeIndexBenchmarks \
  -w 1s -r 1s -rf json -rff build/rule-shape-index-local/repeat.json
```

Use a new output directory for each run. For the original pilot, omit `-w 1s
-r 1s` and use the annotation defaults. The annotation processor used here has
SHA-256 `6a5604b5b804e0daca1145df1077609321687734a8b49387e49f10557c186c77`.

## Correctness evidence

The implementation passed the affected Maven reactor (2,505 tests) and fresh
Gradle core execution (843 tests). A review added explicit normalized rational
leaf, cross-root recognition fallback and assumption-bearing replay controls;
the focused index suite then passed on a fresh core build. These compare the
complete ordered proposals with the existing executor, including bounds,
custom behavior, exact/scoped leaves and rejection of forged assumptions.
