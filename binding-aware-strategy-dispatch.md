# Binding-aware strategy dispatch (experimental v2)

`TraceStrategyDispatchLearner.trainBindingAware` connects shared pattern bindings
to actual continuation training and execution. The original `train` entry point,
v1 canonical policy bytes and four comparison profiles are unchanged.

```java
var formation = TraceStrategyDispatchExample.train().formation();
var learner = new TraceStrategyDispatchLearner();
var policy = learner.trainBindingAware(
    formation,
    TraceStrategyDispatchExample.selectionInputs(),
    TraceStrategyDispatchExample.limits(),
    new TraceStrategyDispatchLearner.BindingLimits(32, 100_000, 20_000));
var prepared = learner.prepare(policy, TraceStrategyDispatchLearner.Profile.LEARNED_DISPATCH);
```

This is an explicit experimental entry point, not a production-default change.

## What is learned and checked

The flat selection-TRAIN paths supply pairs of primitive trajectories with the
same formation-admitted gene sequence. Anti-unification uses one expression-pair
map across *all* states. Thus the same placeholder denotes the same actual
subexpression throughout the path, including scoped symbol identity.
Templates with a wholly unconstrained start, or a new free placeholder appearing
only after the initial state, are rejected. The model retains its supporting
TRAIN IDs and trace hashes; its own content hash binds the matcher revision,
templates, work and limits.

For example, three different TRAIN paths produce this shared template:

```text
(P0+P1)*(P0-P1)+P1*P1+P2
P0^2-P1^2+P1*P1+P2
P0^2-P1^2+P1^2+P2
P0^2+P2
```

The dispatcher checks a prefix before invoking its existing compiled tail, then
checks the complete primitive path. It does not lock the first successful prefix
substitution; complete matching can reconsider earlier commutative alternatives.
All primitive intermediate states and occurrence application keys remain in the
source-bound transformation provenance. A template grants no proof authority:
the existing primitive engines and replay/exact audits retain that responsibility.

The initial implementation generalizes **whole-state trajectories**, not
arbitrary occurrence placements or a newly synthesized rule sequence. Full
CLI/HTTP scoped-input migration and a general tactic learner remain separate.

### Exact constants and structural identity

The binding model uses `regelsuche.trace-binding-model/v2`. Equal numeric TRAIN
literals remain fixed regardless of their size or whether they are integral.
Small integers retain `PatternNumber`; other exact literals use immutable `K`
seed bindings passed to every prefix and complete-path match. These are distinct
from the free `P` placeholders. A known constant may first appear in a later state
without creating a new free variable. Unequal TRAIN numbers may still generalize
through the ordinary shared expression-pair environment.

The `regelsuche.trace-binding-identity/v1` encoding records typed AST nodes in
ordered preorder, including operators, function arities, exact numerators and
denominators, and scoped symbol identities. It distinguishes `(a+b)+c` from
`a+(b+c)` and likewise for multiplication; those trees are not interchangeable
under the existing structural matcher. Display formatting is never a template
key or trace identity. The sorted fixed-literal seed map is part of the template
identity, so different constants cannot share a structural key.

This changes the experimental binding-model schema and its containing v2 policy
hash, not the historical v1 dispatcher hash or production defaults. The existing
matcher revision and mathematical proof/assumption authorities are unchanged.

## Budget and policy behavior

One matching allowance is shared by every template, prefix and complete-path
attempt within one expansion. A failed or incomplete attempt cannot emit a
partially checked continuation. Already completely checked candidates remain
valid; when none exists, the original primitive fallback is used.

The existing selection gate is unchanged: a route must preserve baseline scores
on every selection-TRAIN case and strictly reduce total measured work.
`UNGATED_CONTINUATIONS` removes the learned context-mask gate for diagnostics;
in v2 it still requires shared-binding consistency. It is not an accepted learned
route policy. Both flat profiles retain their historical execution semantics.

Formation work charges inventory/trace preparation events, pair attempts,
generalization nodes, fixed-literal seed formation and binding-closure inspections.
Runtime matching work charges template inspections, AST requests, provenance
visits and the existing matcher's node-work ledger, through
`delegatedMechanicalWorkUnits`. Parsing, hashing, allocation, expression-equality
internals and numerical bit complexity are outside this logical ledger. These
are not walltime/CPU or memory quotas.

## Development evidence (not a held-out benchmark)

On the original implementation head `b0dc456`, local Java-25 source compilation
and 78 JUnit tests passed, including the 17 model/dispatch/occurrence methods,
historical dispatch/transfer examples, scoped matcher tests, primitive minimality
and compiled-engine replay. An additional real-primitive occurrence test rejects
a valid suffix that changes the bound residual; deliberately bypassing full-path
matching made that test fail. The full CI for that head subsequently passed,
but review exposed two uncovered model-integrity faults.

Test-only commit `4c7c095` added nine integrity controls. Its ordinary CI run
`35092576139`, Maven job `104782141531`, compiled the code and ran 742 learning
tests: six failures, zero errors and zero skips. The six expected failures cover
large/decimal literal widening, constant-key collisions, associative grouping
in template and trace identities, and falsely merged training support. The
correction is in `ba2575e`. The source-pinned learning/dependency build in run
`35093740379` completed its compile/test step successfully. This is not a claim
that a later head has passed the complete Maven/Docker/Gradle pipeline.

Additional controls exercise seed immutability, known constants appearing later,
distinct constant slots, retained generalization of unequal numbers, function
arity/argument identity and exact decimal normalization. No successful local
execution is claimed for this review-fix session; its local runtime is unavailable.

### Earlier small-TRAIN cost observation

On the original head's unchanged eight selection-TRAIN inputs, the model formed
**one template**. Binding formation cost **376 logical units**. The flat baseline
cost **235**; the two route trials cost **382** and **902**, so **neither route was
accepted**. Total reported training work was 2,332 units, including existing
formation, context collection and rejected trials. These are retained earlier
observations, not a new performance measurement of the integrity correction.

The diagnostic ungated execution used the learned binding template and retained
the actual replayable three-step primitive derivation. The historical v1 hash is
still checked against
`sha256:170363d5facca1ff53615fa8197bf70df268a9d872e0915eb5f228c42cddaf43`.

No speedup is claimed. Ordinary current-head CI, Maven/Docker qualification and
review remain required. No frozen evaluation input, reference, threshold,
proof/assumption check or repository protection was changed.
