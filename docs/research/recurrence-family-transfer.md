# Learning one-parameter transition families

An additional explicit `generalizeRecurrences(validation, budget)` operation can
now derive a family from previously acquired recurrence laws. It does not run
on ordinary solve calls. Two exact entries must have the same degree bound and
sign evolution and differ in exactly one matrix or offset coefficient. Their
polynomials are normalized to monic form before their coefficients are affinely
interpolated. This normalization avoids treating arbitrary scalar multiples as
different mathematical laws.

The interpolation is **only a candidate**. `RecurrenceInvariantFamily` substitutes
the transition with the differing coefficient kept symbolic and proves the step
identity in the existing exact polynomial representation. An identity true only
at the two example parameters is rejected. In particular, `x -> p*x` preserves
`x^2` at `p=+1` and `p=-1`, but those observations do not authorize that law for
other parameters. No historical formula or special coefficient matrix appears
in the production generalizer. The degree/sign grammar and one-parameter affine
interpolation procedure are supplied algorithms, not an autonomously learned
general proof strategy.

### Promotion, execution and snapshots

At most one validated family is promoted per call. Formation uses only stored
examples. Validation must contain a transition not covered by the predecessor;
new initial values of already covered transitions do not suffice. The previous
success/no-regression and declared-work benefit checks still apply. Failed and
rejected attempts remain paid. The original examples remain available.

On an unfamiliar matching transition, the family is specialized with its actual
rational coefficient. All other transition entries and the degree bound remain
binding. The caller-bound specialization and new initial values are checked by
`RecurrenceInvariantVerifier.verifyFamily`, using the immutable, previously
proved `VerifiedFamily` as step authority. They share the same request budget as
selection and any fallback synthesis. An untrusted `Proposal` still requires the
original `verify` path, which independently replays the entire symbolic step. A specialization that is
constant or zero cannot finish the task. Success remains **one** nonconstant law,
not a claim that the complete invariant space has been enumerated.

The same CumulativeTaskKnowledge catalog stores the family and exact entries.
Only a catalog containing a family uses the new v4 encoding. Existing v1/v2/v3
bytes and fixed-catalog behavior remain unchanged. The snapshot retains two
untrusted anchors, not a trusted symbolic-proof flag. Loading reconstructs and
reproves their universal family relation; two individually valid anchors do not
suffice. Unknown fields, recursive family payloads, conflicting degree bounds,
duplicate keys and a version downgrade are rejected. The total catalog remains
bounded to 32 entries and 200,000 canonical payload characters. Both learning
paths check the actual serialized candidate before promotion, including both
anchors of each family, and charge that inspection to acquisition. An oversized
candidate returns `ACQUISITION_LIMIT` with the previous catalog and paid work;
the successor remains saveable and restorable. Encoding independently enforces
the same bound used by restoration.

```java
// knowledge already contains independently acquired laws from different transitions.
var update = knowledge.generalizeRecurrences(validationTasks,
    new RepresentationBridge.Budget(500_000));
knowledge = update.next();
var result = knowledge.solve(unseenTransitionTask,
    CumulativeTaskKnowledge.Strategy.AUTOMATIC);
```

### Retained development comparison before proof reuse

These results describe the merged `8f051ce1` source, before the following
proof-reuse optimization. They are retained rather than overwritten.

The exact precursor lessons use parameters 1 and 2 of a two-dimensional
transition. Family promotion uses parameters 3 and 5. Evaluation uses different
parameters `4, 7, -3, 0, 11, -7, 1/2`, with eight different initial states each,
plus eight unrelated unsolved one-dimensional controls. These are 64 actual
queries, **not 64 independent families or a sealed test set**. Validation tasks
never supply the family coefficients. No query cache is used. Every arm solves
56/64 tasks. All 56 matching queries reuse the family; the exact-only memory
cannot reuse a law on any evaluation transition.

| Arm | Declared logical work including acquisition and reload |
| --- | ---: |
| Same synthesis baseline | 264,736 |
| Two acquired exact laws, no generalization | 285,848 |
| Acquired laws plus proved family, after reload | 75,595 |

The family total includes 31,166 acquisition/validation units (12,134 for the
family-generalization stage), 3,725 reload/proof units, and 40,704 query units.
This is about 71.4% less declared work than the existing synthesis baseline in
this developmental stream. It is neither a wall-clock speedup nor a complete
JVM/memory/serialization accounting claim. The baseline's current task adapter
still calls its complete bounded synthesizer before selecting one law; this is
not a comparison against every possible specialized first-law solver.

Tests additionally cover a varying affine offset and FIXED rather than
ALTERNATING laws, so the implementation is not a Fibonacci/Pell dispatch table.
The actual persistence test launches separate save and restore JVMs from JUnit
and checks transfer to a previously unseen coefficient after restart. The
payload regression uses 28 genuinely verified entries occupying 199,855
characters, then exercises both rejected learning paths and an actual save/load
round trip. The existing 48-query recurrence comparison now pays the admission
checks: 157,216 baseline / 106,418 frozen / 55,080 cumulative. The 512-query
expression comparison remains 225,536 / 229,264 / 186,706. Existing stored
snapshot encodings remain compatible.

### Remaining limits

One changing coefficient, affine parameter dependence after normalization,
explicit rational affine transitions, and the existing degree/dimension/input
bounds. Multiple independently varying coefficients, nonlinear parameter
families, search for coordinate changes and arbitrary proof-plan grammars are
not implemented. Complete historical coverage (including Euclid and Ferrari)
is not claimed. Symbolic checks use the existing domain-specific exact checker,
not a Lean kernel certificate. The updated measurements and payload regression
run through native Java 25, Maven and JUnit. Hosted checkout-local CI, coverage
and quality gates remain required before merge; the original Java-21 direct
compatibility evidence is historical rather than the current verification path.

```bash
./gradlew --no-daemon :regelsuche-math-algorithms:test --tests '*RecurrenceInvariantFamilyTest'
./gradlew --no-daemon :regelsuche-learning:test --tests '*RecurrenceFamilyKnowledgeTest'
mvn -pl regelsuche-learning -am -Dtest=RecurrencePayloadLimitTest,RecurrenceFamilyKnowledgeTest,CumulativeRecurrenceKnowledgeTest,RecurrenceInvariantFamilyTest -Dsurefire.failIfNoSpecifiedTests=false test
```


### Reuse the proved step instead of proving every instance again

A `VerifiedFamily` already establishes the step identity for every rational
value of its one varying coefficient. The previous query adapter specialized
that family and then independently expanded the entire concrete identity again.
The new `verifyFamily(family, source, initialState, budget)` checks the fixed
transition entries and instantiates the exact polynomial held by that proof
object. It reuses the universal step and runs the **same existing initial-value
checker** as full replay. New initial values are never cached or copied.

The authority object has a private constructor and immutable polynomial fields.
The new API does not accept a public `Specialization` or a saved success flag as
proof. A stored family must still pass anchor and universal-identity checking on
load. There is no new persistent format, process-wide cache, proof bypass for
untrusted proposals, relaxed payload bound, or modified synthesis baseline.
The public original verifier remains available for independent full-step audits.
The specialization text guard, source binding, nonconstant-law requirement,
initial-state guards and a single work budget remain enforced.

This is a proved-lemma instantiation rule inside the existing exact rational
checker, not a new independent theorem prover. Correctness depends on that
checker and the small instantiation path. Tests therefore audit instances with
the original full verifier **and** a separate exact numeric initial-value
evaluator, including rational/negative parameters, fixed and alternating laws,
affine offsets, changed sources, concurrent calls and cutoff budgets.

On the same actually executed 64-query development stream:

| Arm | Before | With proved-step reuse |
| --- | ---: | ---: |
| Same synthesis baseline | 264,736 | 264,736 |
| Exact laws only | 285,848 | 285,848 |
| Learned family, including acquisition and reload | 75,595 | 53,069 |
| Family query portion only | 40,704 | 18,992 |

All arms retain 56/64 successes and the family handles all 56 matching queries.
The new family total is 30,352 acquisition/validation + 3,725 reload/proof +
18,992 query units. The new method reduces the previous family's declared
lifecycle work by about 29.8%; it does **not** establish a wall-clock speedup.
One representative query changes from 602 to 195 units. Independently replaying
that query's complete concrete step costs 551 units; the new method genuinely
avoids work beyond the candidate-selection difference.

The test first failed on unchanged production (602 >= 551), then passed after
the implementation. Four compiled faults are caught: ignored fixed source
entries, accepting an interpolated but false family, resetting the initial-value
budget, and using stale initial values. Existing 48-query recurrence and
512-query expression comparisons remain unchanged at
157,216 / 106,418 / 55,080 and 225,536 / 229,264 / 186,706 respectively.
Existing actual save/load JVM tests and the three payload regressions pass in
the direct local harness. The payload test bodies use explicit local assertion
adapters; their native JUnit sources are unchanged.

Local evidence uses Java 21 with real dependencies and resources. Three existing
Java-25 `ScopedValue` calls are adapted only in a separate compatibility copy;
JUnit annotations are removed only from direct test copies. This is not native
Java-25/JUnit discovery, timeout or complete-product qualification. A combined
local command reached its outer tool time limit; suites were subsequently run
separately to completion, not counted as passing from the timed-out command.
A temporary assertion-adapter boxing error was corrected only in the local
harness; no production or original payload test change was required.

PR #1136's separately retained complete-JVM measurement remains unmodified:
it found no time/CPU amortization through 64 queries for the preceding source.
A new versioned full-lifecycle run is required to determine whether this actual
work reduction changes that result. No new historical task, multi-parameter
family, Ferrari method or Euclid proof is delivered by this optimization.
