# Continuation-safe search quotient

## Intent

Complete the production search-owner part of #661/#696: share exact mathematical
values without discarding continuations that depend on notation, assumptions or
remaining budgets. The user requested implementation of the open topics on
20 November 2026. The existing issue contracts are the requirements.

## Architecture and alternatives

Keep the existing BestFirst executor and its retained primitive paths. Add an
explicit versioned continuation policy; historical policy remains available for
replaying historical experiments. The new profile uses an owner-local semantic
arena. A quotient node contains a value, exact normalized assumptions and a
finite controller identity. Exact syntax is a separate observation label because
the TransformationEngine API may distinguish AC-equivalent notations.

The previous application-set policy cannot merely drop history from its key:
it suppresses future actions based on that history. The new policy instead
rejects dominated arrivals at the same syntax/assumption/controller observation.
Depth and expanding-step use are resource labels. A label dominates another only
if it uses no more of either resource and has at least equal proof strength.
This bounds cycles without forbidding a useful primitive because its textual
application key occurred earlier on a different state. The retained SearchState
still carries the original full path and primitive provenance for replay.

Do not collapse syntax labels just because their mathematical value is shared.
Do not use old cross-run observations as pruning authority. Do not claim a new
mathematical equality from hash equality or from a search result.

## Acceptance

1. Two equal future observations reached by different paths expand once when
   one resource/proof label dominates the other.
2. A later shallower arrival, fewer consumed expansion steps, or stronger proof
   lineage retains its continuation even if a worse arrival was expanded first.
3. Different assumptions, syntax-sensitive futures and controller policies stay
   separate. Exact syntax targets remain reachable.
4. All retained primitive paths reconstruct their transformation lineage.
5. The historical strategy stays available and deterministic. New artifacts name
   the new policy and version.
6. Record exact arena entries, digest computations and deterministic structural
   storage bytes, separately from JVM allocation or wall time.
7. Compare both policies on declared finite controls; preserve losses/nulls and
   do not generalize a synthetic duplicate-work saving to unrelated workloads.

## Boundaries

This tranche does not authorize arbitrary semantic quotienting of an opaque
stateful transformation callback. The adapter is expression-local; its results
are frozen within one search owner. Persistent arena snapshots and broader
native/e-graph consumers are separately tracked acceptance work.
