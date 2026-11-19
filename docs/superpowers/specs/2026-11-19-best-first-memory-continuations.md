# BestFirst continuations with durable memory

## Problem and observed evidence

Issue #696 requires transposition reuse to preserve distinct controller states
and assumptions. At `eb3faf92487ab839ac53c0e3df5b78ada0d7ea11`, a BestFirst
search using only the built-in `ast_power_of_power` rule explores four states
from `((x^2)^2)^2` without memory, including the path through `(x ^ 4) ^ 2`
to `x ^ 8`. With fresh memory it explores two states and loses that reduction.
The first intermediate `(x ^ 2) ^ 4` shares the same canonical value, depth,
score and rule ID, but has already consumed the application needed to continue.
The durable entry omits application history. Syntax-exact searches already
avoid this cutoff; untargeted and value-directed searches do not.

## Chosen contract

All BestFirst modes record durable observations without using them to prune
the current run. The existing typed `VisitedStateKey` continues to identify
run-local states by canonical value, application history and assumptions.
Ordinary candidate filters and budgets remain in force. No second identity
model is introduced.

The existing migration-aware `TranspositionGate.evaluate(..., false)` retains
legacy hash migration, assumption grouping, visit counts and merged rule
observations. Its default pruning behavior remains available to other
strategies. BestFirst emits no transposition-pruned event or decision for
an observation it continues to explore. Public APIs and persisted schemas
remain unchanged. A* and policy-aware BestFirst inherit this behavior.

## Compatibility and limits

Memory-enabled BestFirst can explore more states than before and can consume
more of its existing budgets. Historical memory from a different run, inventory
or profile is an observation, not proof that this run's legal continuations
were explored. No persisted inventory, controller or budget qualification is
available to justify such a proof. A new qualified persistence design is out
of scope.

This change prevents the demonstrated memory-induced loss of a reduced syntax
and score improvement. It makes no general completeness or scaling claim and
does not certify the direct gate consumers in other strategies. Issue #696
remains open for its broader acceptance criteria.

## Validation

Observe a failing JUnit regression with the real built-in rule before the
production change. Compare complete explored states and the concrete reduction
path against no-memory searches with fresh, repeated and warmed memory. Include
continued value-target search, A* inheritance, assumption grouping and legacy
observation telemetry. Retain the existing exact-target and standalone gate
tests. Run the focused regressions and the full search-module suite, then
refresh and check strategy-index anchors. No commit or publication before
parent review.
