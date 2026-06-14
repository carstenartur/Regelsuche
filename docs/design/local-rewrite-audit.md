# Local Rewrite Engine Audit

- Date: 2026-06-14
- Scope: `LocalRewriteApplier`, tree-local enumeration, move realization, and Rule Inspection integration

## Result

`LocalRewriteApplier` itself is generic enough to be treated as a core architectural primitive. It has no `COMPLETE_SQUARE`, `CompleteSquare`, or rule-class branches. Its only dispatch is structural AST navigation (`BinaryExpr`/`FunctionExpr`) and candidate selection by generic move metadata.

## Rewrite path

```
TreeLocalMoveEnumerator
  -> LocalCandidateMove(TreePosition, CandidateMove)
  -> LocalRewriteApplier.apply(rootExpression, position, candidates)
  -> MoveRealizer.realize(subtreeBefore, candidates)
  -> AST subtree replacement
  -> expressionAfter
```

## Couplings found

| Location | Coupling | Assessment |
|---|---|---|
| `LocalRewriteApplier.selectRealizedMove` | Filters realized moves by `candidate.move().kind() == first.kind()` and matching parameter values. | Generic metadata matching, not a mathematical-rule special case. Acceptable for the applier. |
| `LocalRewriteApplier.subtreeAt` / `replaceAt` | Branches on AST node type (`BinaryExpr`, `FunctionExpr`). | Structural coupling to the AST shape. Acceptable and required for AST replacement. |
| `LocalRewriteApplier.apply` | Compares formatted subtree text with `TreePosition.text()` to detect stale positions. | Useful safety check, but it couples position identity to formatted text. Track as position cleanup risk. |
| `RuleInspectionService` | Special-cases `"complete-square"` to collapse `shift` and `residue` into one logical match. | Rule/enumerator coupling outside the applier. This should move into generic logical-match metadata before the Rule IDE grows. |
| `MoveRealizer` | Contains per-enumerator realization methods for equation cancellation, complete square, and repeated subexpression. | Expected at the realization boundary. This is the rule/operator registry layer, not the local rewrite applier. |
| `Depth1MoveEnumerator.kindFor` | Maps enumerator ids to `RewriteMoveKind`. | Centralized metadata mapping. Acceptable short-term; should become registry-backed when third-party rules/plugins arrive. |

## Negative checks

No instances of the following were found in `LocalRewriteApplier`:

- `COMPLETE_SQUARE`
- `CompleteSquare`
- `switch(moveKind)`
- rule-specific `instanceof`
- regex rewrite logic
- string concatenation rewrite logic

## Recommendations

1. Keep `LocalRewriteApplier` rule-agnostic. New rules should only change enumeration/realization registries.
2. Introduce a generic `logicalMatchId` or `matchGroupKey` on `CandidateMove` so `RuleInspectionService` does not know that complete-square has two parameters.
3. Split `TreePosition` identity from stale-snapshot validation: use `TreePosition(path)` plus a separate `expectedSubtreeText` or `expectedSubtreeHash` when needed.
4. Promote the AST lookup/replacement helpers to a reusable core utility once search starts using local rewrites directly.

## Merge assessment

The applier passes the main acceptance rule: it does not need knowledge of individual mathematical rules. Remaining coupling is in UI/realization orchestration and should be tracked as follow-up architecture work, not as a blocker for the applier core.
