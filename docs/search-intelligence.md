# Search Intelligence

## Goals and transforms

The workbench accepts an optional transformation goal on `POST /api/search`.
The default is `SIMPLIFY`; supported values are:

- `SIMPLIFY`
- `FACTOR`
- `EXPAND`
- `CANONICAL_FORM`
- `TEACHING_FRIENDLY`

A goal selects a `CostModel` used by all bundled strategies. Search states carry
per-step provenance (`RewriteKind`, complexity/cost hints and whether a rewrite
is equivalence-preserving by construction). The response mirrors the goal and
includes:

- goal/model metadata;
- selected-state score and estimated-cost breakdown;
- a bounded Pareto frontier over score, cost, depth, complexity and proof
  penalty.

## Search profiles and strategies

Profiles live in `de.regelsuche.search.SearchProfile` and map to a concrete
strategy:

| Profile | Strategy |
| --- | --- |
| `DEFAULT` | `BestFirstSearchStrategy` |
| `EXHAUSTIVE` | `BreadthFirstSearchStrategy` |
| `LOW_MEMORY` | `BeamSearchStrategy` |
| `DEEP_SEARCH` | `AStarSearchStrategy` |
| `STOCHASTIC` | `RandomMonteCarloSearchStrategy` |
| `DIVERSITY_DISCOVERY` | `StructuralDiversitySearchStrategy` |
| `CROSS_FAMILY_DISCOVERY` | `CrossFamilyDiscoverySearchStrategy` |
| `EQUALITY_SATURATION` | `EGraphSaturationStrategy` |
| `PROGRAMMED` | `ProgrammedSearchStrategy` |

Callers may alternatively name a strategy directly using `strategy`.
`SearchStrategyProvider` publishes aliases and metadata for API/UI clients.

`StructuralDiversitySearchStrategy` is a deterministic, target-blind
quality-diversity control. It retains at most one elite per depth-local structural
cell using only observable expression/path descriptors such as AST-size band,
expansion debt, last rewrite kind, denominator count and power count. It does
not receive a target expression or historical identity. The implementation is a
bounded diagnostic control for scalar-fitness valleys, not a claim of complete
MAP-Elites search.

`CrossFamilyDiscoverySearchStrategy` is a deterministic bounded control for
moving between visible expression-structure families. It records only
currently observed structure dimensions, transformation provenance, novelty
against earlier visible cells and a bounded quality/archive allocation. It does
not receive hidden family labels, targets or held-out outcomes and it does not
itself constitute a held-out cross-family experiment.

## Historical rediscovery and reachability atlas

The checkout includes a frozen 14-case diagnostic corpus spanning completing
squares, factorization, temporary expansion, a Sophie-Germain-type bridge, a
search-policy fitness-valley control and a false near-miss. Each case fixes the
source, target, relation, role, provenance and independent oracle/search budgets.

For each supported case the atlas records:

1. representation and equivalence evidence;
2. production-inventory reachability;
3. target-blind scalar best-first search;
4. target-blind structural-diversity search;
5. target-guided search as a diagnostic control.

Preregistered controls add the generic
`DifferenceOfSquaresPreparationOperator` for the Sophie-Germain case and one
curated completing-the-square recognition rule. These controls distinguish a
missing production edge from representation or matcher failure; they never
count as autonomous rediscovery.

The source corpus and strict schemas are:

- `regelsuche-experiments/src/main/resources/de/regelsuche/benchmark/historical-rediscovery-corpus.json`;
- `docs/schemas/regelsuche-historical-rediscovery-corpus-v1.schema.json`;
- `docs/schemas/regelsuche-historical-rediscovery-run-v1.schema.json`;
- `docs/schemas/regelsuche-witness-pruning-diagnostic-v1.schema.json`;
- `docs/schemas/regelsuche-witness-policy-comparison-v1.schema.json`.

Generated JSON and Markdown retain the corpus hash, witnesses, rule IDs,
primitive work, search metrics, directionality and one evidence-derived primary
diagnosis per case. The aggregate assessment is one of
`USEFUL_DIAGNOSTIC_STEP`, `USEFUL_BUT_INCOMPLETE` or `INSUFFICIENT_SIGNAL`.

The output directory is committed as a manifest-last run. Before payload
replacement, any previous `historical-rediscovery-run.json` is removed. The
manifest is written only after the canonical atlas JSON and Markdown exist, and
binds their exact byte lengths and SHA-256 hashes together with the corpus,
inventory, claim boundary, case count and assessment decision. Consumers must
verify the manifest and both payloads; a missing manifest denotes an incomplete
run rather than reusable evidence.

### First lost oracle-witness prefix

For every production-oracle witness that the retained target-blind scalar search
does not recover, the same scalar search is rerun with a passive telemetry
observer. The rerun must reproduce the retained explored-state, engine-call,
generated-transformation and complete goal-metric ledger exactly. Only after
that target-blind run has finished is its telemetry compared with the
previously retained target-aware oracle path.

The diagnostic retains the first missing witness edge and distinguishes, among
other outcomes:

- a witness transformation rejected by an ordinary safety policy;
- duplicate or transposition pruning;
- a per-state candidate ceiling reached before the witness edge;
- a parent stopped at the depth ceiling;
- a generated state left queued when the global state budget ended;
- a production engine that did not emit the oracle edge;
- a witness parent never reached by the target-blind search.

The canonical artifact is written separately under
`regelsuche-experiments/build/reports/historical-rediscovery-witness-pruning/`
as `witness-pruning-diagnostic.json`. It binds the corpus and atlas identities,
production inventory, search policy, oracle and production work ledgers, case
balance, first loss event and a SHA-256 content identity. It is downstream
diagnostic evidence and does not replace or mutate the historical atlas run.

### Scalar versus structural-diversity witness retention

The downstream policy comparison consumes the verified atlas run and the
content-addressed scalar witness-pruning diagnostic. It reruns only the existing
target-blind `StructuralDiversitySearchStrategy` and requires its explored-state,
engine-call and generated-transformation ledger to reproduce the retained atlas
evidence exactly.

For each eligible oracle witness the comparison records the consecutive prefix
explored by the scalar and diversity policies, whether diversity reaches the
retained relation, the prefix gain, both actual work ledgers and the identical
declared `SearchHeuristic` budget. Equal configured budgets are not mislabeled
as equal executed work.

The canonical artifact is written under
`regelsuche-experiments/build/reports/historical-rediscovery-witness-policy-comparison/`
as `witness-policy-comparison.json`. It distinguishes relation recovery, full
witness exploration, positive/no/negative prefix gain and non-applicable cases.
The oracle remains post-hoc diagnostic information and never guides either
search.

The dedicated `regelsuche-core` oracle and known-derivation tests remain the
authoritative unit-level contracts. Atlas tests add only corpus, policy and
cross-layer integration evidence; they do not replace those focused tests.

Generate the atlas, witness-prefix diagnostic and policy comparison with:

```bash
./gradlew :regelsuche-experiments:generateHistoricalWitnessPolicyComparison
```

The atlas output is written under
`regelsuche-experiments/build/reports/historical-rediscovery/` and contains:

- `historical-rediscovery-atlas.json`;
- `historical-rediscovery-atlas.md`;
- `historical-rediscovery-run.json`.

The ordinary checkout-owned merge gate remains:

```bash
./gradlew --no-configuration-cache ciCheck
```

### Claim boundary

The oracle and guided control receive published targets and are therefore
strictly diagnostic. Scalar and diversity searches are target-blind, but the
corpus is still a known historical benchmark. A case may say that a bridge was
required or a production primitive was missing only after the production
oracle exhausted the complete frozen closure; a stopped budget remains
`BUDGET_INCONCLUSIVE`. The `regrouped-square` case is retained as an executable
regression for this fail-closed distinction. The aggregate search-policy signal
requires a target-blind structural-diversity result and is never inferred solely
from the target-guided control. The aggregate equivalence-discrimination signal
requires at least one retained negative control; an empty negative-control
subset cannot pass by vacuous truth. A first lost witness prefix identifies only
where the bounded target-blind execution diverged from a target-aware diagnostic
path. A diversity prefix gain establishes only a bounded policy difference under
the declared corpus and budget; it does not establish global reachability,
autonomous rediscovery, mathematical novelty or general search superiority.
Results establish only bounded reachability and search-policy behavior for the
frozen representation, inventory and budgets.

## Universal patterns

`GlobalMemoryService` scores canonical states by visit count, distinct rule
combinations and age. The leading entries are exposed through:

| Surface | Use |
| --- | --- |
| `GET /api/memory/universal` | JSON list of `patterns[]` and `ruleCoverage[]` |
| Suchgedächtnis → *Universelle Muster* | supporting best path and promotion context |

High-universality states are macro-rule promotion targets. Promotion rules are
documented in [`macro-rules.md`](macro-rules.md).

## Related docs

- [`search-strategies.md`](search-strategies.md) — strategy details.
- [`equality-saturation.md`](equality-saturation.md) — the EGraph layer.
- [`didactic-ranking.md`](didactic-ranking.md) — `TEACHING_FRIENDLY` ranking.

## Equality-saturation runtime metrics

In addition to the general search API, equality saturation exposes
per-rule/per-iteration runtime metrics. `EGraphSaturationStrategy` retains the
latest `EGraphSaturationResult` and the `/api/search` response includes an
`egraph` object containing:

- stop reason and iteration count;
- total e-classes / e-nodes;
- matches, new e-nodes, merges, duplicates and estimated memory;
- one entry per iteration;
- per-rule counters with a derived productive merge rate;
- per-rule scheduling state: finite fuel, deterministic backoff level and
  next eligible iteration;
- a `schedulerPolicy` identifier plus baseline/scheduled match, merge,
  duplicate and estimated-memory deltas;
- proof-DAG node/edge counts, checker work, verified root, extraction objective,
  extracted expression/assumptions and per-proof-kind counts when an extraction
  was requested;
- no hidden total work: default runs stop at the configured
  `MAX_SATURATION_ITERATIONS` or an explicit graph limit; fixed-point detection
  remains opt-in through `stopAtFixedPoint=true` and is reported separately.

The default publication-capable EGraph rule pack contains only native pattern
rewrites. Rules are classified as `NATIVE_SUPPORTED`,
`EXPLICIT_REFERENCE_BRIDGE`, or `UNSUPPORTED_FOR_SATURATION`. The current run
records included rules together with excluded rules and their reasons; reference
bridges remain excluded from the default path and cannot be silently presented
as complete saturation semantics.

Use `GET /api/egraph/metrics` to retrieve the latest snapshot directly. The
workbench renders the same counters in the *E-Graph Laufzeitmetriken* panel,
including scheduler policy, per-rule fuel/backoff and proof-checker status.

## Shared saturation fragment manifest

Regelsuche exports a deterministic manifest for the publication-capable native
EGraph fragment:

```bash
./gradlew :regelsuche-search:exportSharedSaturationFragment
```

The output is written to:

```text
regelsuche-search/build/reports/egraph/shared-saturation-fragment.json
```

The manifest contains the exact native rule inventory, excluded rules with
explicit reasons, supported matcher/guard/assumption features, unsupported
constructs, certificate availability, and a stable `policyHash`. It is intended
as the information-parity contract for #235 before adding an external
term-rewriting or equality-saturation competitor.

## Target-aware best-first diagnostics

`SearchProblem.withTarget(...)` can attach a diagnostic target relation to
best-first, A* and beam search. Target distance changes ordering only: it cannot
change rule applicability, candidate validity or evidence. The returned
`GoalSearchResult` retains:

- target terminal status;
- first reached state;
- best distance and best state when the target was not reached;
- explored, expanded, generated, enqueued, skipped and pruned counters;
- bounded value-identity cache statistics.

The historical atlas uses this only as a target-aware diagnostic control. Its
ordinary scalar and structural-diversity configurations remain target-blind.
