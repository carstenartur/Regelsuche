# Design: Local Rewrite API

- Status: Proposed
- Date: 2026-06-14
- Depends on: ADR `docs/adr/tree-position.md` (TreePosition identity migration)
- Implemented by: (not yet assigned)

## Overview

`TreeLocalMoveEnumerator` produces position-tagged candidate moves. The next
step is to *apply* such a candidate to the subtree it targets, replacing that
subtree in the full expression and returning the new expression together with
proof metadata.

This document specifies the `LocalRewrite` API: its inputs, outputs, proof
status model, and integration with the existing search infrastructure.

## Motivation

The current move pipeline ends at enumeration:

```
Expression → TreeLocalMoveEnumerator → List<LocalCandidateMove>
```

To support tutor mode, game-style navigation, and position-targeted search,
the pipeline must extend to application:

```
Expression
  └─ TreeLocalMoveEnumerator ──→ List<LocalCandidateMove>
                                         │
                              LocalRewriteEngine.apply()
                                         │
                                  RewriteResult
                                  (before, after, position, rule, proofStatus)
```

`RewriteResult` is what the rest of the system can consume: the search engine
records it as an edge, the tutor presents it as a move, the accessibility layer
narrates it, and the Rule Authoring IDE previews it.

## Required inputs

```java
Optional<RewriteResult> apply(Expr root, LocalCandidateMove candidate)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `root` | `Expr` | The full parsed expression tree (already available from the parse step) |
| `candidate` | `LocalCandidateMove` | Position + `CandidateMove` produced by `TreeLocalMoveEnumerator` |

`LocalCandidateMove` carries `TreePosition` (where) and `CandidateMove` (what
kind, which parameter). No additional input is needed; `root` and `candidate`
together fully specify the operation.

The method returns `Optional<RewriteResult>`: empty when the candidate cannot
be realized at the given position (e.g. the subtree no longer matches the move
precondition, or the underlying operator produces no result). Callers must
handle the empty case; `LocalRewriteEngine` never throws for an inapplicable
candidate.

## Expected outputs

```java
record RewriteResult(
    String              before,           // formatted full expression before
    String              after,            // formatted full expression after
    TreePosition        affectedPosition, // where the change was applied
    RewriteMove         rule,             // the fully constructed RewriteMove
    ProofStatus         proofStatus       // confidence level of the equivalence
)
```

`rule` is a `RewriteMove` (existing type in `de.regelsuche.moves`) constructed
from the candidate's kind, parameter, and the before/after pair. It carries the
`moveId`, `ordinal`, and `assumptions` that the search infrastructure already
expects.

`affectedPosition` is a `TreePosition(path)` (path-only, per the ADR). It
identifies which subtree was replaced so that visualization, replay, and
accessibility tools can highlight or describe the change.

## Proof status

```java
enum ProofStatus {
    EQUIVALENCE_PRESERVING,   // operator is formally equivalence-preserving (structural guarantee)
    ORACLE_CONFIRMED,         // external oracle (e.g. SymPy) confirmed equivalence
    ASSUMED,                  // move kind is known to be sound; proof not checked for this instance
    UNKNOWN                   // proof status could not be determined
}
```

Status assignment rules:

| Move kind | Initial status | Can be upgraded to |
|-----------|---------------|--------------------|
| `ADD_SAME_TERM_BOTH_SIDES` | `EQUIVALENCE_PRESERVING` | — |
| `COMPLETE_SQUARE` | `ASSUMED` | `ORACLE_CONFIRMED` (via #102) |
| `COMMON_SUBEXPRESSION` | `ASSUMED` | `ORACLE_CONFIRMED` |
| `FACTOR`, `EXPAND` | `ASSUMED` | `ORACLE_CONFIRMED` |
| `UNKNOWN` | `UNKNOWN` | — |

`ProofStatus` is stored in `RewriteResult` and copied into the `RewriteMove`'s
`validationStatus` field (already supported by `RewriteMove.builder`).

## Implementation sketch

### New type: `ExprReplacer`

The only genuinely new building block. Navigates to a node identified by a
`TreePosition(path)` and returns a new root with that node replaced:

```java
final class ExprReplacer {
    /**
     * Returns a new expression tree with the subtree at {@code path} replaced
     * by {@code replacement}. Returns empty when the path does not exist in
     * {@code root}.
     */
    static Optional<Expr> replace(Expr root, List<Integer> path, Expr replacement) { … }
}
```

This is structurally straightforward: recurse down the path, rebuild parent
nodes on the way back up (immutable AST). The entire existing `Expr` hierarchy
is already immutable.

### `SubtreeRewriteAdapter`

Bridges `LocalCandidateMove` → `MoveRealizer`:

1. Navigate `root` to the subtree at `candidate.position().path()`.
2. Format the subtree as a string.
3. Pass the subtree string and `candidate.move()` to the existing `MoveRealizer`
   (unchanged).
4. `MoveRealizer` returns a `MoveBackedTransformation` containing the rewritten
   subtree string.
5. Parse the rewritten subtree back to an `Expr`.
6. Use `ExprReplacer.replace(root, path, rewrittenSubtree)` to produce the new
   full tree.
7. Format the new full tree as `after`.

### `LocalRewriteEngineImpl`

Orchestrates the above and packages the output as `RewriteResult`. Delegates
proof status assignment based on the move kind.

## Interaction with existing search infrastructure

`RewriteResult` is designed to slot into the existing infrastructure without
requiring changes:

| Existing component | How `RewriteResult` integrates |
|--------------------|-------------------------------|
| `MoveCandidateTransformationEngine` | `RewriteResult.rule` is a `RewriteMove`; `after` becomes the `transformedExpression` of a `Transformation`. No API change needed. |
| `CountableMoveSearchEngine` | Already consumes `List<Transformation>`. A thin adapter converts `RewriteResult → Transformation`. |
| `RewriteMoveDeriver` | Not involved. `LocalRewriteEngine` constructs `RewriteMove` directly from `CandidateMove` metadata. |
| `MoveTreeReportWriter` | Can consume `RewriteMove` from `RewriteResult` unchanged. |
| Oracle / proof cache (#102) | `ProofStatus.ORACLE_CONFIRMED` is set by an oracle post-processor; `LocalRewriteEngine` itself does not call the oracle. |

The integration layer is a thin `LocalRewriteToTransformationAdapter` that maps
`RewriteResult` to `Transformation`. This adapter is the only coupling point;
`LocalRewriteEngine` itself has no dependency on search infrastructure.

## Non-goals for the first implementation

- Full oracle integration for `ORACLE_CONFIRMED` status (deferred to #102).
- Inverse/undo support (deferred; `before` is always stored in `RewriteResult`
  so replay is possible once the inverse move is specified).
- Position-targeted rules from the DSL (`applicablePositions` on
  `HypothesisOperator`): the adapter pattern described in the TreePosition
  issue matrix handles this later without changing `HypothesisOperator`.
- UI rendering or accessibility narration: those layers consume `RewriteResult`
  but are not part of this API.

## Acceptance criteria for implementation PR

- `ExprReplacer` with unit tests covering root replacement, leaf replacement,
  and invalid path handling.
- `LocalRewriteEngineImpl` applying at least `COMPLETE_SQUARE` and
  `ADD_SAME_TERM_BOTH_SIDES` at non-root positions.
- `RewriteResult` carrying `before`, `after`, `affectedPosition`, `rule`,
  `proofStatus`.
- `TreePosition` migrated to path-only identity (per ADR) before or in the
  same PR.
- No changes to `MoveRealizer`, `CountableMoveSearchEngine`, or any report
  writer.
