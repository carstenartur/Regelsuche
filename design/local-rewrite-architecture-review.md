# Local Rewrite Architecture Review

- Date: 2026-06-14
- Question: Is `LocalRewriteApplier` a core architecture block or only a feature?

## Verdict

`LocalRewriteApplier` is a core architecture block. Together with `TreePosition` and `LocalCandidateMove`, it provides the reusable primitive needed by search expansion, discovery visualization, tutor mode, explainability, accessibility, and Rule Authoring IDE workflows.

## Strengths

- Rule-agnostic applier: no mathematical-rule branches in `LocalRewriteApplier`.
- AST-based subtree lookup and replacement.
- Deterministic position model via child-index paths.
- Works for root and nested rewrites.
- Produces both subtree-level and full-expression after states.
- Exposes bindings needed for explanations and future semantic events.

## Risks

- `TreePosition(path, text)` mixes structural identity with display/stale-check data.
- `RuleInspectionService` still contains complete-square-specific grouping behavior.
- `MoveRealizer` is a hard-coded realization registry; this is acceptable now but will become limiting for plugins.
- `LocalRewriteResult` carries string ids instead of the full `RewriteMove`/move metadata.
- Candidate grouping for multi-parameter logical matches is not yet modeled explicitly.

## Position identity cleanup

`TreePosition(path, text)` is useful today because it lets the applier reject stale positions by comparing the current subtree text with the inspected subtree text. Long-term, `TreePosition(path)` is the cleaner identity model.

Recommended target:

```
TreePosition(path)
RewritePrecondition(expectedSubtreeText or expectedSubtreeHash)
PositionDisplay(pathKey, renderedSubtree)
```

Do not refactor immediately unless the surrounding DTO/API changes are included. A partial refactor would remove a useful stale-position guard without replacing it.

## Open points

1. Introduce generic logical match grouping metadata.
2. Promote AST subtree lookup/replacement to a reusable core utility.
3. Add full move metadata to `LocalRewriteResult`.
4. Split position identity from display/precondition data.
5. Define a semantic rewrite event type.
6. Add a search adapter that turns successful local rewrites into successors.

## Suggested follow-up issues

- Add `CandidateMove.logicalMatchId` or equivalent grouping metadata.
- Refactor `RuleInspectionService` to remove enumerator-specific grouping branches.
- Introduce `LocalRewriteEvent` with before/after root/subtree, position, move metadata, bindings, and proof status.
- Migrate `TreePosition` to path-only identity with separate stale-check payload.
- Connect local rewrite successors to search expansion behind a small adapter.

## Merge readiness

The current implementation is suitable to merge as a foundation if the known risks are tracked. The most important merge criterion is met: `LocalRewriteApplier` does not know individual mathematical rules and performs actual AST replacement.
