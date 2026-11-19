# Search Intelligence

This page is the entry point for the mechanisms that make Regelsuche's search
selective and diagnosable: profiles, goals, transposition memory, structural
diversity, rule-directed preparation, bounded reachability and universal
patterns.

For roadmap and historical context see
[`search-intelligence-roadmap.md`](search-intelligence-roadmap.md). This page
describes the currently shipped behavior.

## Profile + Goal

A `SearchProfile` selects a strategy and heuristic preset. A
`TransformationGoal` independently selects the cost model used to compare
candidates.

| Profile | Strategy | Default goal |
| --- | --- | --- |
| `FAST_SIMPLIFY` | BestFirst | `SIMPLIFY` |
| `DISCOVERY` | Hybrid | `SIMPLIFY` |
| `DIVERSITY_DISCOVERY` | target-blind structural quality-diversity | `SIMPLIFY` |
| `TEACHING` | Beam | `TEACHING_FRIENDLY` |
| `PROOF_ORIENTED` | A* | `PROOF_FRIENDLY` |
| `EXHAUSTIVE_SMALL` | MCTS | `SIMPLIFY` |
| `DISCOVERY_PLUS` | BestFirst + durable value observations | `SIMPLIFY` |
| `EQUALITY_SATURATION` | EGraph saturation | `SIMPLIFY` |

The web UI exposes profile and goal separately. `POST /api/search` accepts both;
when `goal` is omitted, the profile default applies. Usage guidance is in
[`user-workflows.md`](user-workflows.md#goal-dropdown).

## Transposition table

`DISCOVERY_PLUS` activates durable `TranspositionTable` observations for
BestFirst. The table aggregates scores, depths, rule IDs and visits by canonical
value and assumption fingerprint. Legacy identity entries are migrated lazily
when the current value identity is observed.

When memory is attached, all BestFirst modes record these observations without
using them to suppress the current run's continuations. This includes untargeted searches, both target
relations, and the inherited A* and policy-aware BestFirst implementations. The
run-local typed visited key includes canonical value, applied rule applications
and assumptions. Candidate filters and search budgets still limit exploration.
The durable summary does not contain the application history, rule inventory,
profile or remaining budget needed to certify that a continuation is reusable.
Observations from another run therefore cannot justify such a cutoff.

BestFirst records no transposition-pruning decisions or events, and its
`transpositionPrunes` metric remains zero. Other strategies that directly use
`TranspositionGate` retain their existing pruning behavior. Their decisions
remain exposed at `/api/memory/pruning`; the durable schema and gate API are
unchanged.

### Continuation regression and limits

Using only the built-in `ast_power_of_power` rule, `((x^2)^2)^2` has two
immediate reductions: `(x ^ 2) ^ 4` and `(x ^ 4) ^ 2`. They share a canonical
value, score, depth and rule ID but have different applied-rule application
sets. The first has already consumed the application needed to reach `x ^ 8`;
the second still permits that continuation.

At `eb3faf92487ab839ac53c0e3df5b78ada0d7ea11`, untargeted BestFirst without
memory explores four states and reaches `x ^ 8` through `(x ^ 4) ^ 2`. Fresh
memory explores two states and loses that reduction; repeated or shallow-run
memory can leave only the root. The production-rule
[`BestFirstMemoryContinuationTest`](../regelsuche-search/src/test/java/de/regelsuche/search/strategy/BestFirstMemoryContinuationTest.java)
compares the complete explored states and concrete reduction path with the
same no-memory search for fresh, repeated and warmed memory, a continued value
target, and A* with observations from BestFirst. All five comparisons pass
after the cutoff is removed. The telemetry and goal-aware
tests also retain assumption separation, legacy migration and repeated
syntax-exact paths.

The observed loss concerns a reduced syntax and better expression score: the
value canonicalizer already recognizes their equivalence. The restricted
production inventory isolates this failure; it is not a full-default-inventory
performance comparison. Memory-enabled BestFirst may now explore more states
and use more of its budgets. No general completeness or scaling result follows,
and this change does not qualify the other strategies' durable pruning.
Issue #696 remains open for the broader search-scaling and reusable-knowledge
criteria.

## Structural diversity

`DIVERSITY_DISCOVERY` is a deterministic target-blind diagnostic profile. At
each depth it retains at most one elite per structural cell instead of reducing
all survivors to one scalar ranking. Cells use only observable TRAIN-side
structure:

- AST-size band;
- expansion debt;
- last rewrite kind;
- denominator-count band;
- power-count band.

No target identity is inspected. The profile is a bounded
MAP-Elites-style control for scalar-fitness valleys, not a claim to implement
the complete MAP-Elites algorithm.

## Rule-directed preparation

The preparation layer diagnoses and bridges expressions that are close to a
visible rule without directly matching it. `UnifiedRulePreparationCoordinator`
uses the following policy order:

```text
concrete direct executor replay
  -> typed guards
  -> native exact preparation specialist
  -> bounded pattern-targeted local bridge
  -> concrete replay and independent verification
```

`SafePreparationEngineRegistry` binds the existing exact specialists to their
execution order and native principal rule IDs. A similar imported or learned
pattern does not inherit another rule's specialist contract. The local bridge
uses an explicitly frozen equivalence-preserving preparation inventory and
retains every primitive step, assumption and work limit.

Technical exceptions become retained `TECHNICAL_FAILURE` outcomes. The unified
policy is implemented and characterized, but has not yet been selected as the
general Workbench/CLI default profile. Full contracts and limitations are in:

- [`safe-rule-preparation-coordinator.md`](safe-rule-preparation-coordinator.md);
- [`rule-directed-preparation-planning.md`](rule-directed-preparation-planning.md);
- [`learned-pattern-rule-promotion.md`](learned-pattern-rule-promotion.md).

## Semantic hypothesis operators and learning boundaries

`HypothesisOperatorRegistry` is the deterministic source of the hypothesis
operators selected by the discovery profiles. Its current order and default
status are:

| Rule ID | Default | Role |
| --- | ---: | --- |
| `hypothesis_polynomial_decomposition_synthesis` | yes | exact bounded coefficient synthesis for supported quartics |
| `hypothesis_difference_of_squares_preparation` | no | historical Sophie-Germain bridge control |
| `hypothesis_complete_square_preparation` | yes | conservative quadratic bridge |
| `hypothesis_telescoping_fraction` | yes | conservative adjacent-denominator decomposition |
| `hypothesis_rationalize_denominator` | yes | conservative conjugate rationalization |

The polynomial synthesis operator first interprets complete AST subtrees as
structural polynomial atoms and then solves a bounded exact coefficient system.
It is not a named Sophie-Germain rule, and its certificate does not turn the
operator into a complete polynomial factorizer. See
[`polynomial-decomposition-synthesis.md`](polynomial-decomposition-synthesis.md).

Learned rules use a separate trust boundary. A narrow assumption-free
polynomial pattern can receive an exact identity proof and a new experimental
rule identity, but raw compiled genomes remain untrusted. Generation-separated
campaigns freeze the current shadow inventory before candidate formation and
activate accepted rules only in the next generation. They do not mutate the
normal production inventory or change `PROMOTION = NOT_EVALUATED`. See
[`generational-rule-mining.md`](generational-rule-mining.md).

## Bounded reachability and historical atlas

`BoundedRewriteReachabilityOracle` performs deterministic breadth-first
traversal over the directed successors of a frozen `TransformationEngine`. It
retains a shortest witness and reports exactly one of:

- `REACHABLE`;
- `UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE`, only after complete finite-closure
  exhaustion;
- `BUDGET_INCONCLUSIVE`, when a depth or visited-state bound blocks an unseen
  successor.

The historical rediscovery atlas applies this boundary to a versioned 14-case
corpus and compares four explicitly separated layers:

1. production-inventory reachability;
2. target-blind scalar best-first search;
3. target-blind structural-diversity search;
4. target-guided search as a diagnostic control.

Preregistered controls add the generic
`DifferenceOfSquaresPreparationOperator` for the Sophie-Germain case and one
curated completing-the-square recognition rule. These controls distinguish a
missing production edge from representation or matcher failure; they never
count as autonomous rediscovery. Their historical role is unchanged by the
newer default polynomial synthesis operator.

The source corpus and strict schemas are:

- `regelsuche-experiments/src/main/resources/de/regelsuche/benchmark/historical-rediscovery-corpus.json`;
- `docs/schemas/regelsuche-historical-rediscovery-corpus-v1.schema.json`;
- `docs/schemas/regelsuche-historical-rediscovery-run-v1.schema.json`;
- `docs/schemas/regelsuche-witness-pruning-diagnostic-v1.schema.json`;
- `docs/schemas/regelsuche-production-search-comparison-v1.schema.json`.

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

### Matched declared-budget production-search comparison

The next downstream diagnostic compares the scalar witness prefix with a fresh
run of `StructuralDiversitySearchStrategy`. Both target-blind policies use the
same source, production rule inventory and declared `SearchHeuristic` ceilings.
The diversity rerun must reproduce the diversity result, explored-state count,
engine-call count, generated-transformation count, selected path and rule IDs
already retained in the atlas.

The report keeps declared ceilings and consumed work separate for each policy.
It classifies whether structural diversity:

- recovers the complete selected oracle witness;
- reaches the relation through another path;
- retains a longer, equal or shorter oracle-witness prefix;
- is not comparable because the production oracle retained no witness;
- or is unnecessary because scalar best-first already reached the relation.

The content-addressed artifact is written under
`regelsuche-experiments/build/reports/historical-rediscovery-production-search-comparison/`
as `production-search-comparison.json`. It binds the corpus, atlas and
witness-diagnostic identities, exact information boundary, per-case declared
budgets, both consumed-work ledgers and the complete status balance.

The dedicated `regelsuche-core` oracle and known-derivation tests remain the
authoritative unit-level contracts. Atlas tests add only corpus, policy and
cross-layer integration evidence; they do not replace those focused tests.

Generate the atlas and both downstream diagnostics with:

```bash
./gradlew :regelsuche-experiments:generateHistoricalRediscoveryAtlas
```

The atlas output is written under
`regelsuche-experiments/build/reports/historical-rediscovery/` and contains:

- `historical-rediscovery-atlas.json`;
- `historical-rediscovery-atlas.md`;
- `historical-rediscovery-run.json`.

The witness-prefix and matched-budget artifacts use their separate sibling
report directories described above.

The [historical representation calibration](research/historical-intrinsic-calibration/result.md)
publishes all retained scalar and diversity occurrences from the 14 public cases,
their source-equivalence judgments and their post-search representation matches.

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
path. Equal declared search ceilings do not mean that scalar and diversity
consumed equal work; the report retains those ledgers independently. A longer or
complete diversity prefix is not a proof of global reachability, autonomous
rediscovery, mathematical novelty or general search superiority. Results
establish only bounded reachability and search-policy behavior for the frozen
representation, inventory and budgets.

For a `VALUE_EQUIVALENT` case, semantic equality to the known target alone
does not count as rediscovery: the unchanged source is already equivalent for
every valid identity. After the search has finished, the atlas additionally
uses the existing representation correspondence classifier. It requires a
positive-depth occurrence in the target's associative/commutative structural
class, and rejects a source that already belongs to that class. This is a
conservative post-search diagnostic, not an unrestricted test for all useful
factorizations or a mathematical proof; the target is not supplied to either
target-blind search. The existing value-equivalence check remains separate.
The search evidence records `POST_SEARCH_AC_REPRESENTATION_V1` in
`detail` for this criterion. The target-aware oracle uses the same target
relation while retaining separate search-state identities; it does not merge
states simply because their representations correspond. A source already in the
target representation is reported separately from a rediscovery. A guided run
can terminate upon value equivalence before reaching the requested representation;
its terminal status records that mismatch. New atlas outputs use schema `v2`,
and older `v1` runs remain readable. Compare counts only after rerunning the
same evaluation revision. Previously distributed v1 validators reject reports
that reference the v2 atlas or use the added status values; update those
validators before consuming new output.

## Universal patterns

`GlobalMemoryService` scores canonical states by visit count, distinct rule
combinations and age. The leading entries are exposed through:

| Surface | Use |
| --- | --- |
| `GET /api/memory/universal` | JSON list of `patterns[]` and `ruleCoverage[]` |
| Suchgedächtnis → *Universelle Muster* | supporting best path and promotion context |

High-universality states are macro-rule promotion targets. Promotion rules are
documented in [`macro-rules.md`](macro-rules.md). This heuristic macro context
is distinct from the exact learned-pattern promotion mechanism.

## Related docs

- [`search-strategies.md`](search-strategies.md) — strategy details.
- [`equality-saturation.md`](equality-saturation.md) — the EGraph layer.
- [`didactic-ranking.md`](didactic-ranking.md) — `TEACHING_FRIENDLY` ranking.
- [`generational-rule-mining.md`](generational-rule-mining.md) — frozen learned-rule generations.
- [`polynomial-decomposition-synthesis.md`](polynomial-decomposition-synthesis.md) — exact bounded semantic synthesis.

## Equality-saturation runtime metrics

`EQUALITY_SATURATION` additionally reports:

- `classesScanned`, `nodesScanned`, `candidateClassesSkipped`;
- `matchesFound`;
- `matcherCacheHits`, `matcherCacheMisses`;
- `saturationIterations`, `rulesFired`.

The fields are available in `SaturationStats` and the benchmark JSON/Markdown
artifacts.
