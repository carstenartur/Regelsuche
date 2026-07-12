# Expression identity benchmark evidence

This document records the bounded experiment used by ADR #242. It is evidence for
an architecture decision, not a production performance promise.

## Run

- GitHub Actions workflow: **Benchmark**, run **479**
- Workflow run: https://github.com/carstenartur/Regelsuche/actions/runs/29185051458
- Branch commit: `59fefc29aa816031511d3d69560c7a763b12412b`
- JDK: Temurin 21.0.11
- JMH: 1 fork, 2 warm-up iterations, 3 measurement iterations, 1 second each
- Mode: average time, microseconds per operation

The temporary benchmark implementation was captured in the referenced commit and
removed after the measurement so that architecture scaffolding does not permanently
increase the project's AI-context and maintenance budget. This document preserves
the inputs, measurements and conclusions needed for the ADR.

## Results

| Benchmark | Mean (µs/op) | Error (µs/op) | Interpretation |
|---|---:|---:|---|
| `dualSemanticProjectionAllocatesPerOccurrence` | 77.224 | 9.688 | Build a non-interned canonical value projection for a 256-expression AC corpus |
| `internedProjectionWithFreshScope` | 115.989 | 47.877 | Build the same corpus with a new scoped interning factory |
| `internedProjectionWithWarmScope` | 110.636 | 26.546 | Re-project through a factory whose values are already interned |
| `treeEvaluationOfRepeatedSubexpressions` | 1.23194 | 0.08768 | Evaluate a syntax tree containing 32 repetitions of `(a+b)*(a+b)` |
| `dagEvaluationOfRepeatedSubexpressions` | 0.178929 | 0.006203 | Evaluate the interned value DAG with identity-based memoization |

## Derived observations

1. The deliberately simple hash-consing implementation made projection
   approximately **1.50× slower** with a fresh scope and **1.43× slower** with a
   warm scope than allocating a separate canonical value tree.
2. The interned DAG evaluated the repeated-subexpression corpus approximately
   **6.88× faster** than the tree evaluator.
3. Therefore interning is not justified as a transformation performed repeatedly
   inside each matcher or rule. It is justified as a value layer that is built once
   per bounded owner (parse bundle, search session or compilation unit) and reused by
   caches and evaluators.
4. The warm-scope result did not eliminate construction overhead because canonical
   keys and candidate value objects still had to be built for lookup. Production
   work should optimize this only after profiling; reference identity is primarily
   a correctness and cache-sharing facility, not a claim that hash-consing is free.

## Structural measurements from the executable spike

The spike also established deterministic object-count facts:

- For `(a+b)+c`, `a+(b+c)` and `c+a+b`, there are **15 concrete syntax
  occurrences** in total.
- A separate semantic-tree projection allocated **15 semantic value nodes**.
- One scoped interning factory retained **7 distinct values**: `a`, `b`, `c`, three
  distinct partial sums and one shared full sum.
- `(a+b)*(a+b)` has **7 concrete occurrences** but only **4 values** under the
  selected AC value policy: `a`, `b`, `a+b` and the product.
- A normal `Set<Occurrence>` retained the two uses of the same `a` value because
  occurrence identity was explicit.

## Limits

- The factory was a minimal `HashMap`-based prototype, not a tuned production pool.
- The evaluator corpus intentionally contains repeated pure subexpressions; other
  expression distributions will produce smaller gains.
- The benchmark does not measure source-span storage, serialization, e-graph
  saturation or all rule-migration costs.
- The result supports a layered design but does not justify global interning or
  replacing the current syntax AST in one migration.

## Decision signal

The evidence favors an **interned immutable value DAG plus explicit occurrence
identity**, provided that:

- the factory is scoped and owned;
- projection is cached rather than rebuilt for each rule;
- stable structural keys remain available across scopes and persistence;
- syntax occurrences and `TreePosition` remain the local-rewrite address space;
- e-classes remain a broader, dynamic equivalence layer.
