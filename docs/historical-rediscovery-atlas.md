# Historical rediscovery and reachability atlas

## Purpose

The atlas answers a narrower and more useful question than “did the current
search find the target?” For every frozen case it separates:

1. representation and parsing;
2. independent equivalence evidence;
3. directed reachability in the frozen production rewrite graph;
4. target-blind scalar search;
5. target-blind structural-diversity search;
6. target-guided diagnostic search;
7. a generic hypothesis bridge where preregistered;
8. an explicitly marked curated recognition control.

This separation prevents three common misreadings:

- a budget miss is not proof of graph-theoretic unreachability;
- a curated target-aware rule is not autonomous rediscovery;
- a mathematically equivalent target need not be connected by the currently
  directed production inventory.

## Frozen corpus

The source-controlled corpus is
`regelsuche-experiments/src/main/resources/de/regelsuche/benchmark/historical-rediscovery-corpus.json`.
Its content SHA-256 is retained in every generated report. The first version
contains fourteen cases:

- five completing-the-square variants;
- two difference-of-squares forms;
- common-factor extraction on both operand orientations;
- two reverse/expansion directions;
- the Sophie-Germain identity;
- one information-parity search-policy control;
- one non-equivalent near-miss.

The corpus is a diagnostic fixture. Its provenance labels identify standard
published identities and synthetic controls, but they do not claim historical
priority or publication-grade bibliographic completeness.

## Evidence layers

### Production primitives

`AstRewriteTransformationEngines.production(...)` is executed with the normal
first-party AST rule inventory. The bounded breadth-first oracle reports only:

- `REACHABLE` with a shortest retained witness;
- `UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE` after actual finite-closure
  exhaustion;
- `BUDGET_INCONCLUSIVE` when a declared depth or state bound blocks an unseen
  successor.

### Target-blind scalar search

The existing `BestFirstSearchStrategy` runs without a target. Its ordinary
score, depth, expansion and non-improvement penalties determine the queue.
The published target is used only after the run to check whether one explored
state matched it.

### Target-blind structural diversity

`StructuralDiversitySearchStrategy` retains one elite per structural cell at
each depth. Cells use only AST-size band, expansion debt, last rewrite kind,
denominator count and power count. They never inspect the target identity.
This is a bounded MAP-Elites-style diagnostic control, not an implementation or
performance claim for the complete MAP-Elites algorithm.

### Target-guided diagnostic search

Best-first search is also run with a declared syntax or value target. Guidance
may change ordering and early termination, but not rule applicability. This run
is diagnostic and cannot be counted as autonomous rediscovery.

### Generic bridge and curated control

The Sophie-Germain case preregisters the generic
`DifferenceOfSquaresPreparationOperator`. It introduces a reusable hidden-
structure bridge rather than a direct Sophie-Germain target rule.

Completing-the-square cases additionally use one explicitly marked generic
curated recognition rule. This establishes that the representation and matcher
can express the family; it is not production-inventory or autonomous-discovery
evidence.

## Derived diagnoses

The report derives one primary status per case, including:

- production-reachable and found by scalar search;
- production-reachable but missed by scalar search and recovered by diversity
  or target guidance;
- production-reachable but missed by every bounded production search;
- generic bridge required and successful;
- curated control reaches while production does not;
- complete frozen closure exhausted without the target;
- budget inconclusive;
- negative control confirmed;
- correctness regression.

A final assessment is emitted only from retained statuses. It can be
`USEFUL_DIAGNOSTIC_STEP`, `USEFUL_BUT_INCOMPLETE` or `INSUFFICIENT_SIGNAL`.
This assessment does not authorize a novelty or publication claim.

## Reproduction

Generate only the atlas:

```bash
./gradlew :regelsuche-experiments:generateHistoricalRediscoveryAtlas
```

Run the complete checkout-owned merge gate:

```bash
./gradlew --no-configuration-cache ciCheck
```

The generated files are retained under:

```text
regelsuche-experiments/build/reports/historical-rediscovery/
├── historical-rediscovery-atlas.json
└── historical-rediscovery-atlas.md
```

The JSON is the machine-readable evidence. The Markdown file is a compact human
review surface; it must not be edited independently of the executable report.

## Decision rule for continuing this line of work

The approach is a useful step when the same frozen corpus demonstrably
separates at least several of the following mechanisms:

- working production primitives;
- absent or one-directional inventory;
- a search-policy/fitness-valley miss under matched work;
- a capability frontier moved by a generic bridge;
- preserved rejection of the negative control.

It is an insufficient direction when the atlas cannot distinguish these
mechanisms, produces only budget-inconclusive outcomes, or requires direct
case-specific target rules to manufacture every positive result.
