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
generalization nodes and binding-closure inspections. Runtime matching work
charges template inspections, AST requests, provenance visits and the existing
matcher's node-work ledger, through `delegatedMechanicalWorkUnits`. Parsing,
hashing, allocation, expression-equality internals and numerical bit complexity
are outside this logical ledger. These are not walltime/CPU or memory quotas.

## Current development evidence (not a held-out benchmark)

Local Java-25 source compilation and 78 JUnit tests pass, including all 17 new
model/dispatch methods, historical dispatch/transfer examples, scoped matcher
tests, primitive minimality checks and compiled-engine replay. The new feature
tests were exercised against compiling no-feature scaffolds first. A separate
regression demonstrates rejection of later unbound placeholders. An additional
real-primitive occurrence test rejects a valid suffix that changes the bound
residual; deliberately bypassing full-path matching makes that test fail.

On the unchanged eight selection-TRAIN inputs, the model forms **one template**.
Binding formation costs **376 logical units**. The flat baseline costs **235**;
the two route trials cost **382** and **902**, so **neither route is accepted**.
The final v2 policy therefore falls back to flat selection. This is a negative
utility result, not evidence of a speedup. Total reported training work is 2,332
units, including the existing formation, context collection and rejected trials.

A diagnostic un-gated execution does use the learned binding template, emits
the actual three-step primitive derivation and passes independent replay and
exact equivalence checks. The historical v1 policy hash remains
`sha256:170363d5facca1ff53615fa8197bf70df268a9d872e0915eb5f228c42cddaf43`.

The focused local run is not a replacement for ordinary current-head CI,
Maven/Docker qualification or review. No frozen evaluation inputs, references,
thresholds, proof/assumption checks or repository protections were changed.
