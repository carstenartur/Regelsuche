# Tree Position – Open Issue Alignment Matrix

- Date: 2026-06-14
- Source issues: #102, #103, #104, #105, #106

This matrix maps each open issue to the two new abstractions introduced in
PR #129 and its follow-up design work:

- **TreePosition** — stable structural address of a subtree within an expression.
- **LocalRewrite** — the ability to apply a candidate move at a specific subtree
  position and produce a new full expression (see `docs/design/local-rewrite.md`).

## Matrix

| # | Issue | Uses TreePosition | Uses LocalRewrite | Notes |
|---|-------|:-----------------:|:-----------------:|-------|
| [#102](https://github.com/carstenartur/Regelsuche/issues/102) | Discovery Engine Roadmap | ✅ | ✅ | Position-targeted candidate generation enables subtree-scoped discovery campaigns. `LocalRewrite` produces the before/after pair for evidence and ablation. |
| [#103](https://github.com/carstenartur/Regelsuche/issues/103) | Search Space Intelligence | ✅ | 🟡 Indirectly | `TreePosition` is needed to count unique affected positions per rule (branching factor per depth level). `LocalRewrite` is not required for metrics collection, but is needed when the engine acts on predictions. |
| [#104](https://github.com/carstenartur/Regelsuche/issues/104) | Plugin Ecosystem | 🟡 Stable API surface | 🟡 Stable API surface | Plugins that provide new operators will expose `applicablePositions(root)` once the `PositionAwareOperator` extension point is defined (see Phase 6 in the design doc). `LocalRewrite` is the contract external plugins must conform to. |
| [#105](https://github.com/carstenartur/Regelsuche/issues/105) | Discovery Visualization & Explainability | ✅ | ✅ | Before/After highlighting requires knowing which position changed — supplied by `RewriteResult.affectedPosition`. Discovery Timeline and Hidden Structure visualization both need the position to highlight the correct subtree. |
| [#106](https://github.com/carstenartur/Regelsuche/issues/106) | Rule Authoring IDE | ✅ | ✅ | AST Viewer needs addressable nodes (`TreePosition`). Rewrite Preview is exactly `LocalRewrite.apply(root, candidate)`. Why-not Debugger needs to know which positions a rule was attempted at. |

## Dependency summary

```
TreePosition (PR #129)
    │
    ├─→ LocalRewrite (next PR, see docs/design/local-rewrite.md)
    │       │
    │       ├─→ #102 Discovery Engine (position-targeted campaigns)
    │       ├─→ #105 Visualization (before/after, subtree highlighting)
    │       └─→ #106 Rule IDE (Rewrite Preview, Why-not Debugger)
    │
    ├─→ #103 Search Space Intelligence (position-level branching metrics)
    └─→ #104 Plugin Ecosystem (PositionAwareOperator API surface)
```

`TreePosition` is required by all five issues.  
`LocalRewrite` is required by three issues (#102, #105, #106) and useful for
the remaining two (#103, #104) once those issues expand beyond metrics to
active search guidance.

## Recommended sequence

1. Migrate `TreePosition` to path-only identity (ADR `docs/adr/tree-position.md`).
2. Implement `ExprReplacer` + `LocalRewriteEngine` + `RewriteResult` (design `docs/design/local-rewrite.md`).
3. Connect `LocalRewrite` output to `MoveCandidateTransformationEngine` (thin adapter, no search changes).
4. Add `SubtreeAnnotator` for #105 / #106 semantic labels.
5. Add `PositionAwareOperator` adapter for #102 / #104 discovery campaigns.
6. Oracle confirmation of `ProofStatus` for #102 evidence pipeline.
