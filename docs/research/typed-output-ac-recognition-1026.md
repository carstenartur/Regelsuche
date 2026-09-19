# Optional algebraic recognition for learned output patterns (#1026)

## Scope and contract

Close the reversed-product development gap after the unchanged integration
#1034. Exact recognition remains the default. An explicit RecognitionProfile
constructor reuses ExprMatcher's structural ADD/MUL recognition; algebraic
binding inference and external equivalence exploration are not enabled here.
No new generalizer, domain-specific learned rule or arithmetic auditor is added.

Applications retain their recognition profile and immutable matcher trace as
untrusted metadata, not proof receipts. The original four-argument Application
constructor remains available for exact callers. Literal source reconstruction
is required for exact recognition; algebraic proposals require independent
conditional verification instead. Both substituted sides are structurally
bounded before allocation. Unchanged matched outputs, as well as unmatched
outputs, retain their physical positions and original Expr objects.

## Underlying matcher repair

Simply supplying the existing profile exposed a pre-existing matching failure:

```text
pattern: f(A*B, B)
input:   f(x*y, x)
```

The old matcher committed A=x/B=y inside the first argument. When the second
argument rejected B=y, it discarded the entire match rather than trying A=y/B=x.
The same problem affected noncommutative siblings and nested AC choices.

The existing EquivalenceAwarePatternMatcher now retains pending constraints and
lazy alternative binding snapshots. A later constraint can reopen an earlier
choice within the same pattern. Ordered siblings use explicit tasks rather than
one Java stack frame per argument. The original commutative operand and branch
limits remain; every explored choice consumes the same shared budget. Exhaustion
returns INCONCLUSIVE with the original bindings, not false absence. Only a whole
successful continuation publishes bindings. Existing first-choice ordering,
literal semantics and bounded monomial operations remain in place.

This repairs choices within a PatternExpr. It is not a claim that arbitrary
external matcher combinators enumerate every possible substitution.

## Development verification

The design and integration tests were committed before implementation in
d8535b2b. Run 35448417106 executed the deliberately exact-only API scaffold:
11 integration cases, 7 failures, zero XML errors/skips.

Optional recognition alone at 9e331fb8 was insufficient. Run 35448619625 executed
27 old/new integration cases and failed five reversed-factor cases. This failure
led to the underlying matcher diagnosis rather than weakened expectations.

Seven direct core regressions were added in de4e265f without changing the old
matcher. Run 35448823946 failed four assertions, with zero errors/skips: later
function constraints, a noncommutative sibling, nested AC choices and budget
exhaustion. Caller-binding rollback, compatible-first-choice cost and a wide
4096-argument function remained successful controls.

The repair is tested on QA commit b09607e22a720cb61dcd3679c12da8530ce2bf0c in
run 35449100419. The focused core and app integration steps have completed
successfully; the full core/learning/experiments module step is still running at
this update. This is not a full repository or performance qualification.
The production PR retains the exact four Java blobs from that QA source and
omits its temporary branch-only workflow.

Reproduce:

```sh
./gradlew --no-daemon --no-configuration-cache :app:test \
  --tests 'de.regelsuche.benchmark.TypedModPowAcTransferIntegrationTest' \
  --tests 'de.regelsuche.benchmark.TypedModPowTransferIntegrationTest'
./gradlew --no-daemon --no-configuration-cache \
  :regelsuche-core:test :regelsuche-learning:test :regelsuche-experiments:test
```

## Learning and evidence boundaries

The actual target-blind public TRAIN search supplies witnesses. They are checked
before typed generalization and transferred to renamed operands, both product
orders, reordered outputs and extra outputs. Every positive transferred program
must pass the unchanged ModPowCompositionReplay with concrete assumptions.
Missing/stale assumptions and mismatched shared bindings remain negative controls.

These are development integration tests, not a frozen held-out #1026 evaluation,
a new theorem, automatic promotion or a measured runtime improvement. Generalized
premises, the frozen model/corpus and fair fixed-budget utility remain separate
work. Full current-head repository CI, including existing performance gates, is
required before merge. No original benchmark ceiling, proof/promotion gate,
primitive baseline, dependency or branch protection is changed.
