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
A valid ansatz direction whose normalized text exceeds the 4,096-character
proposal guard, or which the verifier reports as `UNSUPPORTED`, yields an
explicit `UNSUPPORTED` report without laws instead of an exception; the
representation guards are not raised and no direction is silently dropped.
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
benchmark or cumulative-learning comparison. The subsequent integration into
`CumulativeTaskKnowledge` is documented below. Neither slice synthesizes arbitrary
proof plans, infers a true infinite recurrence from observations, or implements
the remaining historic Euclid/Ferrari tasks. The existing finite-fitting domain
remains unchanged.

### Initial standalone validation and implementation ledger

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

## Integration into cumulative task knowledge

`CumulativeTaskKnowledge` now also owns a bounded recurrence catalog. There is
no second persistent learner: expression schemata and recurrence proposals share
one immutable generation, parent identity, acquisition ledger and snapshot.
`learnRecurrences(lessons, validation, budget)` delegates mathematical discovery
to `RecurrenceInvariantSynthesis` and checks every reuse with
`RecurrenceInvariantVerifier`. Ordinary `solve(request, strategy)` never learns.

The typed recurrence request asks for **one nonconstant fixed or alternating
law within its degree bound**. A stored law is not evidence that a whole
nullspace has been enumerated. The full-basis synthesis API remains available
separately. For recurrence tasks `BASELINE` always invokes synthesis;
`KNOWLEDGE_FIRST` and `AUTOMATIC` use matching source-bound knowledge, otherwise
fall back within the same budget. This is deterministic source/degree routing,
not a newly learned general strategy grammar. Every successful reuse checks the
caller's transition and new initial state, rather than caching the old answer.

Lessons use the frozen parent. Candidate activation requires validation on
separate initial-state tasks, coverage of every newly proposed transition, no
lost validation success, and either less measured validation work or additional
success. Merely reusing an existing law does not create another capability.
Failed, duplicate, rejected and acquisition-limited batches keep their paid
work. A failure in an unfinished technical operation exposes incomplete
accounting rather than authorizing a successor. The catalog is bounded to 32
entries. This first adapter transfers across initial states of the same exact
transition; it does not generalize a symbolic family of transition matrices.

Snapshots carrying recurrence data use v3; old v1/v2 snapshots retain their
canonical bytes. Load validates strict fields, bounds and exact rational
components and re-proves every recurrence identity, including its claimed
degree. A hash, saved strategy or historical cost counter is not proof. Learning
in either domain preserves the other domain's payload. Acquisition and
restoration use the existing shared cost categories. Costs still describe
bounded logical operations, not complete JVM, serialization or wall-clock work.

### Executed mixed-stream development check

Two actual learning blocks acquire Fibonacci and Pell laws, with distinct
initial states for validation. Saved generation one is the frozen control;
saved generation two is the cumulative control. Both are loaded again before
48 real requests: 16 rounds with changed initial states for the two known
transitions and an unhelpful one-coordinate transition. No answer cache is used.

| Arm | Declared total work | Successful requests |
| --- | ---: | ---: |
| Same synthesis baseline | 157,216 | 32/48 |
| Acquire one transition, freeze and reload | 106,293 | 32/48 |
| Acquire both transitions, reload | 54,704 | 32/48 |

The cumulative total includes 18,656 acquisition/validation units, 1,360
restoration units and 34,688 query units. This is about 65.2% less declared work
than the same synthesis baseline, not a runtime factor. These are variants of
three development transitions, not 48 independent families or a sealed holdout.
The benefit establishes avoiding repeated coefficient search after acquiring
these laws, not autonomous invention of an invariant grammar or general
superiority to alternative solvers.

The nine new cumulative test methods cover generations, budgets, source/degree
binding, falsified payloads, preservation of expression schemata, negative
acquisition, the mixed stream and actual child JVMs. The mathematical test suite
also now invokes its proposal save/restore subprocesses from JUnit, addressing
the earlier review gap rather than only demonstrating restart through `main`.
Every child has a bounded wait, checked exit status, retained output and forced
cleanup on failure; classpaths include Gradle's separate worker loaders.

Final direct execution: 123 cumulative assertions and 465 mathematical
assertions, including real save/restore child processes. Four compiled fault
injections are rejected (lost source binding, free final proof, trusting stored
claims, dropping the previously learned domain). Existing task-completion and
three-generation expression contracts keep their original results, including
225,536 / 229,264 / 186,706 for the 512-request comparison.

Local integration execution uses Java 21 and the real seven-module production
sources/resources. A separate, unpublished compatibility copy uses Java 21's
`ScopedValue.Carrier.get(Supplier)` at three pre-existing Java-25 `call` sites;
only that copy and the JUnit-free direct test copies are altered for the local
harness. This is not a Java-25/JUnit-discovery, timeout, coverage or native-JDT
qualification. The normal Gradle command was attempted but dependency download
failed because network DNS is unavailable. The complete unchanged hosted CI and
independent review remain required. No workflow, threshold, baseline or original
reference result is changed by this integration.

```java
var knowledge = CumulativeTaskKnowledge.empty(genome);
var update = knowledge.learnRecurrences(lessons, validation, new Budget(500_000));
knowledge = update.next();
var path = knowledge.save(directory);
var restored = CumulativeTaskKnowledge.load(genome, path);
var result = restored.solve(recurrenceRequest, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
```

```bash
./gradlew --no-daemon :regelsuche-learning:test --tests '*CumulativeRecurrenceKnowledgeTest'
./gradlew --no-daemon :regelsuche-math-algorithms:test --tests '*RecurrenceInvariantSynthesisTest'
```
