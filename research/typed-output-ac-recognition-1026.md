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

## Executed development verification

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

The repair was tested on QA commit
`b09607e22a720cb61dcd3679c12da8530ce2bf0c` in run **35449100419**. All three
Gradle commands completed successfully under Temurin **25.0.4+1**. The seven
focused core regressions passed, then the old/new app integration passed, then
all core, learning and experiments tests passed. The final JUnit-XML summary is:

| Executed scope | Suites | Tests | Failures/errors/skips |
| --- | ---: | ---: | ---: |
| Complete core module | 123 | 840 | 0/0/0 |
| Old and new app transfer integration | 2 | 27 | 0/0/0 |
| Complete learning module | 165 | 852 | 0/0/0 |
| Complete experiments module | 65 | 258 | 0/0/0 |

These are **1,977 disjoint tests**, not the full repository suite. The seven
focused core cases are already included in 840 and are not added again.
The source working tree remained clean. Retained artifact **10585892403** has
workflow-reported SHA-256
`20e1cc8fc6ac0df7b64f42a45cbbf60637deb9adee40b0c41acd3226f49f5466`.
This is a retained identifier, not a claim of local ZIP rehashing.

The production PR retains the tested Java blobs:

- core matcher: `a0557f64283360b7c266a3c77a26bd81a892fbb3`;
- core regressions: `83c8f0ac0d3fc36f66616422011ee9c08e28eafa`;
- output matcher: `0acfe8171dc13ec9055933956fe9ddcd87c4fc4f`;
- app regressions: `f63d57b44fcccb969ea391635d1c7e979b40427f`.

The temporary QA workflow is absent from the production diff. Full current-head
repository CI and performance gates remain required before merge.

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
work. No original benchmark ceiling, proof/promotion gate, primitive baseline,
dependency or branch protection is changed.
