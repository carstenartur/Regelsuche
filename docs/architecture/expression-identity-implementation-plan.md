# Expression identity implementation plan

This plan translates ADR #242 into incremental production changes. It deliberately
keeps the accepted architecture separate from the implementation PRs.

## Phase 1 — immutable value core

Deliver in `regelsuche-core`:

- `ExprValue` variants for atoms, ordered operators, functions and selected AC
  operators;
- deterministic `ValueKey` serialization and hashing;
- explicit operator-law metadata;
- a bounded `ExprValueFactory` with scoped hash-consing;
- adapters from the current `Expr` syntax tree;
- tests for AC equality, multiplicity, non-commutative roles, cross-scope stable
  keys and factory lifecycle.

Do not change `Expr.equals`, existing constructors or syntax-rule APIs.

## Phase 2 — typed occurrence index

Evolve existing occurrence infrastructure:

- introduce `OccurrenceId`;
- associate `TreePosition`, syntax `Expr` and `ExprValue` in one occurrence record;
- add forward and reverse lookup;
- retain source grouping, order and staleness checks;
- preserve compatibility with `TermOccurrenceIndex` and local rewrites;
- migrate `AstVisitorContext` metadata to explicit occurrence identity when an
  occurrence context is available.

A normal `Set<Occurrence>` is valid for unordered uses. Mathematical operator
multiplicity remains a value-layer contract.

## Phase 3 — canonicalization and search adapters

- derive stable hashes from `ValueKey` while retaining the existing
  `ExpressionCanonicalizer` API;
- build one value graph per bounded parse/search owner and reuse it;
- migrate transposition, analysis and repeated-subexpression caches incrementally;
- measure construction cost, retained values and cache hit rate;
- avoid rebuilding the value graph inside individual rule matches.

## Phase 4 — recognition and e-graph integration

- let PR #241 recognition reuse value normalization where its profile permits;
- retain syntax-sensitive, algebraic-binding and broader-equivalence recognition;
- map `ExprValue` into e-nodes without equating value identity with e-class identity;
- preserve assumption fingerprints and saturation budgets.

## Phase 5 — optional compiled execution

- prototype an evaluator or matcher cache keyed by `ExprValue`;
- compare tree execution, DAG memoization and compilation overhead on realistic
  corpora;
- select method handles or bytecode generation only through a separate measured
  decision.

## Required migration safeguards

- no unbounded global intern pool;
- no mutable parent/source/runtime state on shared values;
- stable structural identity across serialization;
- local replacement always addresses an occurrence;
- syntax rules remain usable throughout migration;
- deterministic formatting never defines value equality;
- production rollout is operator-by-operator and benchmarked.

## Completion criteria

The implementation program is complete when:

1. ordinary value equality no longer depends on canonical strings;
2. all concrete occurrences remain independently selectable and explainable;
3. search can reuse typed value keys without semantic regressions;
4. PR #241 and e-graph integrations share value normalization instead of duplicating
   it;
5. lifecycle, memory and performance measurements meet project budgets;
6. compatibility adapters for the old canonical hash path can be retired through a
   separately reviewed change.
