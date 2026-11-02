# Exact linear-recurrence discovery domain

## Scope

This slice implements the second candidate language preregistered for the
candidate-independent `finite-difference-recurrences` challenge in issue #383:

```text
a_n = c_1 a_(n-1) + c_2 a_(n-2) + ... + c_k a_(n-k)
```

The frozen profile continues to record `LINEAR_RECURRENCE` as
`ADAPTER_REQUIRED`, because that describes the state at corpus freeze time.
This implementation does not rewrite that historical artifact.

## Exact candidate formation

`LinearRecurrenceSequenceDomain` searches increasing orders from 1 through
the frozen `maximumOrder` and uses exact `BigInteger` rational arithmetic.
A candidate is emitted only when:

- the observed prefix contains at least `2 * order` terms;
- the coefficient system is consistent and has full column rank;
- a unique coefficient vector exists;
- the recurrence replays every observed term exactly.

Floating-point fitting, tolerances and post-hoc order enlargement are not
used. A finite prefix with no uniquely determined model remains
`INCONCLUSIVE`; it is not turned into a guessed recurrence.

## Independent holdout evaluation

The selected model is evaluated only after formation against the supplied
holdout continuation. The certificate records exact coefficients and all
replayed terms, with evidence strength:

```text
LINEAR_RECURRENCE_FINITE_DATA_VALIDATION_NOT_FORMAL_PROOF
```

This is deliberately not a claim that a finite prefix has one unique infinite
continuation.

## Frozen-corpus characterization

`CandidateIndependentLinearRecurrenceCorpusTest` runs the production domain
against the six sequence cases frozen before implementation:

| Case | Lowest unique observed-prefix recurrence | Outcome |
|---|---|---|
| `case-07` | no unique model within order 4 | `INCONCLUSIVE` |
| `case-08` | `a_n = 2 a_(n-1)` | `CONFIRMED` |
| `case-09` | `a_n = -a_(n-1) + a_(n-2) + a_(n-3)` | `CONFIRMED` |
| `case-10` | `a_n = a_(n-1) + a_(n-2)` | `CONFIRMED` |
| `case-11` | `a_n = 35/9 a_(n-1) - 49/9 a_(n-2) + 26/9 a_(n-3)` | `REFUTED` by the frozen holdout |
| `case-12` | `a_n = 4 a_(n-1) - 5 a_(n-2) + 2 a_(n-3)` | `CONFIRMED` |

The `case-11` result is important negative evidence: an exact recurrence can
interpolate the visible prefix and still fail to generalize. The test retains
that refutation and verifies byte-deterministic evidence plus balanced
resource accounting.

Run the focused checks with:

```bash
./gradlew :regelsuche-discovery:test \
  --tests de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomainTest
./gradlew :regelsuche-release:test \
  --tests de.regelsuche.release.CandidateIndependentLinearRecurrenceCorpusTest
```

## Remaining benchmark integration

This slice makes the production candidate form and frozen-corpus behavior
reviewable. Issue #383 still requires the canonical multi-campaign run
artifact, strict run schema, independent aggregate verifier and combined
coverage report with the existing finite-difference adapter.

## Claim boundary

A green result establishes exact bounded recurrence formation and held-out
validation for the frozen sequence corpus. It does not establish formal proof,
external novelty, universal sequence inference, information-parity
superiority or completion of the full three-challenge benchmark.

## Exact invariant synthesis for an explicitly supplied recurrence

`RecurrenceInvariantSynthesis` in `regelsuche-math-algorithms` adds a separate
mathematical capability, not a stronger claim for the finite-sequence fitting
certificates described above. Its input is an explicit affine transition over
exact rationals, `x(n+1) = A*x(n) + b`, and an initial state. An observed prefix
alone never authorizes an identity for all subsequent indices.

### Design and implementation contract

The existing polynomial projection, `LinearSystemRepresentationBridge`, and
`ExactRrefSolver` perform the work. No second rational type, parser, elimination
algorithm or expression-search core is introduced. For one to three coordinates
and degree one or two, a full monomial ansatz is generated. For each multiplier
`+1` and `-1`, coefficient comparison turns `P(A*x+b) = multiplier*P(x)` into
an exact homogeneous linear system. Its nullspace supplies candidate polynomial
laws; constant-only directions are discarded and the first nonzero ansatz
coefficient normalizes the others. No historical formula or desired polynomial
is an input to formation. The ansatz degree and multiplier family are supplied
capabilities, not an automatically invented strategy.

`RecurrenceInvariantVerifier` independently substitutes into the candidate and
compares exact polynomial normal forms. It does not trust the coefficient
solver, historical name, stored success flag, or sampled values. Substitution
is simultaneous and termwise; large intermediate strings are not an excuse to
raise the existing projection safeguards. The independently calculated initial
value supplies the base case. The checked universal step and the fixed induction
scheme establish `P(x(n)) = multiplier^n * P(x(0))` for every `n >= 0`.
This is a domain-specific symbolic induction certificate, not a Lean/kernel
certificate or a general induction-tactic implementation.

All solver stages and final verification share one non-resetting logical-work
budget. Exhaustion returns `BUDGET_INCONCLUSIVE` without a complete set of laws.
`COMPLETE` means the declared coefficient problem was completed, not that all
possible mathematical invariants were enumerated. An empty degree-one result
must not be interpreted as ruling out a quadratic or nonpolynomial invariant.
Matrices/offsets and initial values are bounded to 128-bit rational components.
Existing exact projection limits also apply. The counters do not measure every
JVM allocation, validation operation, serialization operation, or wall-clock
cost. No total-runtime improvement is inferred from them.

### Java API and Cassini development case

```java
var transition = new RecurrenceInvariantSynthesis.Transition(
    new ExactLinearSystem.ExactMatrix(List.of(
        List.of(Rational.ZERO, Rational.ONE),
        List.of(Rational.ONE, Rational.ONE))),
    new ExactLinearSystem.ExactVector(List.of(Rational.ZERO, Rational.ZERO)));
var request = new RecurrenceInvariantSynthesis.Request(
    transition, List.of(Rational.ZERO, Rational.ONE), 2,
    new RepresentationBridge.Budget(200_000));
var result = new RecurrenceInvariantSynthesis().solve(request);
var law = result.laws().getFirst();
System.out.println(law.proposal().polynomial());
System.out.println(law.valueAt(BigInteger.TEN.pow(100)));
```

The executed development case derives `x0^2 + x0*x1 - x1^2` with multiplier `-1`
and initial value `-1`. With state `(F_n,F_(n+1))`, its meaning is
`F_n*F_(n+2) - F_(n+1)^2 = (-1)^(n+1)`. `valueAt` evaluates this invariant's
value via parity; it does **not** calculate `F_n` or claim a closed form for all
coordinates. The production implementation contains no Fibonacci/Cassini name
or special coefficient vector. The same solver derives the Pell counterpart
`x0^2 + 2*x0*x1 - x1^2` from its different transition.

### Reuse and limits of the learning claim

`Proposal` is an untrusted value that callers may store. Reloading requires
`RecurrenceInvariantVerifier.verify` with the caller's own transition and initial
state; only that verifier can issue an immutable `VerifiedLaw`. A changed
transition, wrong multiplier, foreign variable, zero/constant candidate or false
identity cannot inherit the old authority. A new initial state produces a newly
computed initial value. The tests exercise an actual second JVM, storing only
polynomial text and multiplier, not a trusted success bit.

In the directly executed example, synthesis plus independent checking costs
4,350 declared units. Reloading the proposal and checking it for initial state
`(2,1)` costs 545 units and proves alternating values starting at 5, with no
coefficient search. This is a narrow reuse observation, not a full lifecycle
benchmark or cumulative-learning comparison. It does not yet insert recurrence
proofs into `CumulativeTaskKnowledge`, synthesize arbitrary proof plans, infer a
true infinite recurrence from observations, or implement the remaining historic
Euclid/Ferrari tasks. The existing finite-fitting domain remains unchanged.

### Validation and implementation ledger

- Tests precede the new API; the first compile fails because it is absent.
- Eleven JUnit methods cover Cassini/Pell, rational and affine transitions,
  degenerate nullspaces, linear/quadratic bounds, exact values above 2^53,
  forged/rebound proposals, initial-value changes and request-budget boundaries.
- A fixed generated panel adds 36 two-/three-dimensional affine transitions;
  each emitted law is compared with independent exact trajectory evaluation.
- A broader test first exposed a projection-size failure. Termwise projection
  fixes it without altering the existing syntax bound or mathematical checker.
- The direct contracts pass 455 assertions. Four compiled mutations are rejected:
  omitted alternating synthesis, skipped step verification, accepted constant
  laws and ignored sign parity. Save/load is executed in separate JVMs.
- Final local compilation uses the **unchanged** production dependency closure
  (123 class files), the real Jackson dependencies and Java 21, without preview
  flags or production compatibility edits. Only JUnit imports/annotations are
  removed from a separate direct-test copy. This does not qualify JUnit discovery,
  timeout enforcement, the full Java-25 build or the original JDT analyzer.
- Full CI and independent review remain required; no workflow or threshold is
  weakened. This capability is opt-in through the new API, not an automatic
  change to existing search behavior.

The context baseline is separately advanced to the already successful main
`ba92352be07a13af8687e4a64bc516609d0f9fd6`, CI `41341012022`, not to this feature.
That accepted main was already 14,920 units above the old 15,000-unit ratchet
allowance. The retained audit preserves the previous baseline and report hashes;
all indexed artifact-file hashes are checked before projecting the native
metrics. The allowance stays 15,000. This is baseline maintenance, not a new local
extractor measurement or permission for the feature to exceed its allowance.

```bash
./gradlew --no-daemon :regelsuche-math-algorithms:test \
  --tests '*RecurrenceInvariantSynthesisTest'
mvn -pl regelsuche-math-algorithms -am \
  -Dtest=RecurrenceInvariantSynthesisTest -Dsurefire.failIfNoSpecifiedTests=false test
./gradlew --no-daemon --no-configuration-cache ciCheck
```
