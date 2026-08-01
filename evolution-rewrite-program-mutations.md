# Deterministic evolution of rewrite-program topology

Status: bounded mutation foundation for #521

## Purpose

`EvolutionRewriteProgramPlan` makes a strategy program executable and
content-addressed. `DeterministicRewriteProgramMutator` adds the next required
boundary: topology changes are enumerated reproducibly, checked against the
same genome and program budgets, and retained as accepted or rejected attempts.

The mutator does not choose a winner and does not inspect VALIDATION or FINAL
TEST outcomes. It only creates TRAIN-eligible candidate plans under a frozen
mutation catalog, seed and proposal budget.

## Supported mutations

The controlled mutation vocabulary is:

| Mutation | Effect |
|---|---|
| `WRAP_REPEAT` | Add bounded repetition around one existing node. |
| `WRAP_REQUIRE` | Add one supported hard requirement. |
| `WRAP_PRIORITY` | Add one supported deterministic ordering. |
| `WRAP_PRUNE` | Add explicit candidate truncation with a retained reason. |
| `PREPEND_SOURCE` | Build a sequence with one genome-rule source before a node. |
| `APPEND_SOURCE` | Build a sequence with one genome-rule source after a node. |
| `SWAP_ADJACENT_CHILDREN` | Exchange adjacent ordered sequence/choice children. |
| `REMOVE_WRAPPER` | Remove `Repeat`, `Require`, `Prioritize` or `Prune`. |
| `CHOICE_TO_FIRST_APPLICABLE` | Replace union semantics with ordered fallback. |
| `FIRST_APPLICABLE_TO_CHOICE` | Replace ordered fallback with union semantics. |

A mutation can only use genes, requirements, priorities, repetition bounds and
pruning limits present in the frozen `MutationCatalog`. Unknown genome genes
block the catalog before proposal generation.

## Deterministic proposal order

For one parent plan:

1. every topology node is visited in deterministic preorder;
2. proposals are formed from the normalized catalog;
3. proposal keys are sorted;
4. the sorted list is rotated by the declared seed;
5. at most `maxProposals` attempts are evaluated;
6. at most `maxAccepted` structurally distinct children are retained.

Changing the seed changes the bounded proposal window, not the mutation
semantics. The same inputs produce the same attempts, blockers, child hashes
and mutation-batch hash.

## Fail-closed evaluation

Every proposed child is rebuilt through
`EvolutionRewriteProgramPlan.create(...)` and compiled through
`EvolutionRewriteProgramCompiler`. A proposal is rejected when, among other
reasons:

- construction violates node identity, depth or node-count limits;
- a source or preference references an unavailable gene;
- repetition exceeds `maxApplicationsPerPath`;
- a primitive-step guard exceeds that same path budget;
- pruning exceeds `maxCandidatesPerState`;
- the child is identical or alpha-structurally duplicate;
- the accepted-child budget is exhausted;
- ordinary genome or program preflight fails.

Rejected proposals are not omitted. Their ordinal, controlled mutation kind,
proposal key, available child identities and sorted blocker list remain in
`regelsuche.evolution-rewrite-program-mutation-batch/v1`.

The strict Draft 2020-12 schema is
[`regelsuche-evolution-rewrite-program-mutation-batch-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-mutation-batch-v1.schema.json).

## Example

```java
MutationCatalog catalog = new MutationCatalog(
    List.of(new RepeatBounds(1, 2)),
    List.of(Requirement.maxPrimitiveSteps(4)),
    List.of(Priority.estimatedCostThenRule()),
    List.of(8, 16),
    List.of("factor_square_difference", "cancel_common_factor")
);

MutationBatch batch = new DeterministicRewriteProgramMutator().mutate(
    genome,
    parentPlan,
    catalog,
    20260801L,
    new MutationLimits(200, 24)
);
```

`batch.acceptedPlans()` contains executable children. `batch.attempts()` is the
authoritative complete proposal accounting.

## Claim boundary

This mutation layer establishes deterministic formation and lineage of bounded
program topologies. It does not establish positive fitness, population-level
diversity, checkpoint equivalence, VALIDATION selection, FINAL TEST utility,
formal proof or external novelty.

The next #521 integration must treat the genome and program plan as one
candidate identity inside population evaluation and checkpoint/resume. The
flagship rational/polynomial corpus must still be frozen before evaluated
program search begins.
