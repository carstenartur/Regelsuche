# Development follow-up: admit paths after an observed repetition

Revision: `regelsuche.task-query-reuse/v2-second-observation`. This is a development
response to the public v1 observation, not a new independent holdout. The complete
v1 reports and its protocol remain unchanged in this directory.

V1 reduced the repeated 128-query arm from 124,482 to 119,667 logical units,
including all acquisition, preparation and audit work. Its distinct-source arm
increased from 124,482 to 194,450 units: every first success paid a complete
retained-witness footprint traversal despite no subsequent use. This avoidable
admission work motivates the following general policy.

On a first successful nonempty path, retain only its exact query key as a bounded
observation. Reuse the normal solver's already-produced canonical source document
to bound retained source characters, together with normalized assumptions. Do not
serialize the source again or traverse the complete witness graph for admission.
On the next successful occurrence of the same source, assumptions, built-in goal,
all budget dimensions and strategy in the same immutable generation, run the
normal solver again, then prepare and retain the checked path. Only subsequent
queries use that path as proposals and receive both fresh verification passes.

One LRU capacity and character limit cover observation keys and prepared paths
together. Oversized observations, unsuccessful results, unavailable work, budget
failures and interruption grant no retained proof authority. Marker lookup,
admission, replacement and eviction remain paid. The public session remains
explicitly opt-in; arbitrary objective callbacks use fresh ordinary execution.

The following panel is fixed before the v2 measurements: exactly the same inputs,
capacities, three scenarios, three repetitions, alternating arm order, budgets
and full audits as `protocol.md`. The repeated arm can now have 84 warm hits,
because two successful executions precede reuse; the cold and distinct arms must
retain no full paths. Keep their actual extra observation costs visible. Do not
alter the v1 results or describe this adaptation as preregistered independent
confirmation. Report both deterministic work and every observed wall/CPU pair.
