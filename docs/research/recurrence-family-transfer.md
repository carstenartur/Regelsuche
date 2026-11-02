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
binding. The concrete proposal and the caller's new initial values are checked
again by `RecurrenceInvariantVerifier`, within the same request budget as
selection, specialization and any fallback synthesis. A specialization that is
constant or zero cannot finish the task. Success remains **one** nonconstant law,
not a claim that the complete invariant space has been enumerated.

The same CumulativeTaskKnowledge catalog stores the family and exact entries.
Only a catalog containing a family uses the new v4 encoding. Existing v1/v2/v3
bytes and fixed-catalog behavior remain unchanged. The snapshot retains two
untrusted anchors, not a trusted symbolic-proof flag. Loading reconstructs and
reproves their universal family relation; two individually valid anchors do not
suffice. Unknown fields, recursive family payloads, conflicting degree bounds,
duplicate keys and a version downgrade are rejected. The total catalog remains
bounded to 32 entries, and the existing payload-size guards are unchanged.

```java
// knowledge already contains independently acquired laws from different transitions.
var update = knowledge.generalizeRecurrences(validationTasks,
    new RepresentationBridge.Budget(500_000));
knowledge = update.next();
var result = knowledge.solve(unseenTransitionTask,
    CumulativeTaskKnowledge.Strategy.AUTOMATIC);
```

### Executed development comparison

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
| Two acquired exact laws, no generalization | 285,472 |
| Acquired laws plus proved family, after reload | 74,709 |

The family total includes 30,280 acquisition/validation units (11,624 for the
family-generalization stage), 3,725 reload/proof units, and 40,704 query units.
This is about 71.8% less declared work than the existing synthesis baseline in
this developmental stream. It is neither a wall-clock speedup nor a complete
JVM/memory/serialization accounting claim. The baseline's current task adapter
still calls its complete bounded synthesizer before selecting one law; this is
not a comparison against every possible specialized first-law solver.

Tests additionally cover a varying affine offset and FIXED rather than
ALTERNATING laws, so the implementation is not a Fibonacci/Pell dispatch table.
The actual persistence test launches separate save and restore JVMs from JUnit
and checks transfer to a previously unseen coefficient after restart. Existing
exact-only recurrence and expression-learning regressions remain unchanged.

### Remaining limits

One changing coefficient, affine parameter dependence after normalization,
explicit rational affine transitions, and the existing degree/dimension/input
bounds. Multiple independently varying coefficients, nonlinear parameter
families, search for coordinate changes and arbitrary proof-plan grammars are
not implemented. Complete historical coverage (including Euclid and Ferrari)
is not claimed. Symbolic checks use the existing domain-specific exact checker,
not a Lean kernel certificate. Local evidence uses the documented direct
Java-21 compatibility harness; hosted Java-25/JUnit/coverage/quality verification
and independent review are required for this extended head.

```bash
./gradlew --no-daemon :regelsuche-math-algorithms:test --tests '*RecurrenceInvariantFamilyTest'
./gradlew --no-daemon :regelsuche-learning:test --tests '*RecurrenceFamilyKnowledgeTest'
```
