# Rule Authoring Workflow Readiness

- Date: 2026-06-14
- Target: #106 Rule Authoring IDE

## Desired loop

```
Expression
  -> select position
  -> show rule matches
  -> apply rewrite
  -> new expression
  -> inspect again
```

## Current support

| Step | Status | Current component |
|---|---|---|
| Parse expression | Supported | `ExpressionParser`, `TreeLocalMoveEnumerator` |
| Enumerate positions | Supported | `TreeLocalMoveEnumerator` |
| Select position | Supported by data model | `TreePosition.pathKey()` and position DTOs |
| Show rule matches | Supported | `RuleInspectionService.inspect` |
| Show bindings | Supported | `RuleInspectionDto.Binding` |
| Preview subtree rewrite | Supported | `LocalRewriteApplier` via `RuleInspectionService` |
| Preview full expression after rewrite | Supported | `RuleInspectionDto.RuleMatch.expressionAfter` |
| Apply rewrite | Supported at backend primitive level | `LocalRewriteApplier.apply` |
| Re-analyse new expression | Supported by re-calling inspect | no session state required |

## Missing pieces for a full IDE

1. Stable logical match identity for grouping multi-parameter matches without rule-specific UI logic.
2. A command endpoint that applies a selected match by stable id, not by reconstructing DTO fields in the client.
3. Clear stale-position conflict responses when the user edits the expression between inspection and apply.
4. Position-only `TreePosition` identity with separate display/stale-check payload.
5. Rule authoring diagnostics for why a rule did not match at a selected position.
6. Persistent or shareable rewrite trace for tutorials and debugging.

## Assessment

The core loop is already possible with the current backend primitives. The remaining work is mostly API hardening: stable match ids, cleaner position identity, explicit conflict reporting, and why-not diagnostics.
