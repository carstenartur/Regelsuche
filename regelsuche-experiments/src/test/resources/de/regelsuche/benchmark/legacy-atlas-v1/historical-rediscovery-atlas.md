# Historical rediscovery and reachability atlas

- **Corpus SHA-256:** `53bff974a9ecb101b85e01f202d095fb2a99f5f33127ed7ec0e4ade2685ac422`
- **Inventory revision:** `production-ast-rules-plus-generic-hypothesis-controls/v1`
- **Assessment:** `USEFUL_DIAGNOSTIC_STEP`

> Target-aware reachability and published-identity rediscovery diagnostics only. A positive result is not external novelty, and a bounded miss is not global unreachability unless the declared finite closure is exhausted.

| Case | Family | Primary diagnosis | Production oracle | Scalar | Diversity | Guided |
|---|---|---|---|---:|---:|---:|
| complete-square | COMPLETING_THE_SQUARE | CURATED_CONTROL_ONLY_MISSING_PRODUCTION_PRIMITIVE | UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE | no | no | no |
| difference-of-squares | FACTORIZATION | REACHABLE_AND_SCALAR_FOUND | REACHABLE | yes | yes | yes |
| difference-of-squares-powers | FACTORIZATION | REACHABLE_AND_SCALAR_FOUND | REACHABLE | yes | yes | yes |
| distribution-fitness-valley-control | SEARCH_POLICY_CONTROL | REACHABLE_BUT_SCALAR_MISSED_DIVERSITY_FOUND | REACHABLE | no | yes | yes |
| expand-binomial-square | TEMPORARY_COMPLEXITY | REACHABLE_AND_SCALAR_FOUND | REACHABLE | yes | yes | yes |
| factor-common-left | FACTORIZATION | REACHABLE_AND_SCALAR_FOUND | REACHABLE | yes | yes | yes |
| factor-common-right | FACTORIZATION | REACHABLE_AND_SCALAR_FOUND | REACHABLE | yes | yes | yes |
| fractional-square | COMPLETING_THE_SQUARE | CURATED_CONTROL_ONLY_MISSING_PRODUCTION_PRIMITIVE | UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE | no | no | no |
| inconsistent-near-miss | NEGATIVE_CONTROL | NEGATIVE_CONTROL_CONFIRMED | BUDGET_INCONCLUSIVE | no | no | no |
| regrouped-square | COMPLETING_THE_SQUARE | BUDGET_INCONCLUSIVE | BUDGET_INCONCLUSIVE | no | no | no |
| reordered-square | COMPLETING_THE_SQUARE | CURATED_CONTROL_ONLY_MISSING_PRODUCTION_PRIMITIVE | UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE | no | no | no |
| reverse-difference-of-squares | TEMPORARY_COMPLEXITY | REACHABLE_AND_SCALAR_FOUND | REACHABLE | yes | yes | yes |
| scaled-square | COMPLETING_THE_SQUARE | BUDGET_INCONCLUSIVE | BUDGET_INCONCLUSIVE | no | no | no |
| sophie-germain | HIDDEN_STRUCTURE_BRIDGE | GENERIC_BRIDGE_REQUIRED_AND_FOUND | UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE | yes | yes | no |

## Directionality

- **binomial-square:** `REVERSE_ONLY`
- **difference-of-squares:** `BIDIRECTIONAL`

## Assessment reasons

- production primitives recover at least one frozen identity
- curated control separates a missing production primitive from representation failure
- target-blind scalar and structural-diversity searches differ under the same declared heuristic budget
- a generic hypothesis bridge moves the reachable capability frontier
- the false near-miss remains rejected
