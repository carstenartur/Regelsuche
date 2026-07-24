# ADR: TreePosition identity and text field

- Status: Proposed
- Date: 2026-06-14
- PR: #129

## Context

`TreePosition` was introduced in PR #129 as a value type that identifies a
subtree within an expression. Its current definition is:

```java
public record TreePosition(List<Integer> path, String text)
```

`path` is the child-index path from the root (empty = root; `[0]` = first
child; `[1, 0]` = right child's first child, etc.).  
`text` is the infix rendering of the subtree at enumeration time.

Because Java `record` derives `equals`, `hashCode`, and `toString` from all
components, `text` participates in identity. Two `TreePosition` values that
describe the same structural location in different expressions — or the same
location before and after a rewrite — are considered unequal if their subtree
text differs.

The current call-site in `TreeLocalMoveEnumerator` stores the formatted text
into the record at traversal time:

```java
String text = MoveExpressions.format(positioned.expr());
TreePosition position = new TreePosition(positioned.path(), text);
```

`text` is then used as a secondary sort key in `CANONICAL_ORDER` to break ties
among positions at the same path depth.

## Problem

### 1. Identity conflates structure with content

A path unambiguously identifies a location within a fixed tree. Adding `text`
to identity means:

- Two `TreePosition` objects describing the same subtree slot before and after
  a rewrite are **not equal**, even though they refer to the same structural
  location.
- Storing a `TreePosition` as a map key or set element to track "which
  positions have been visited / rewound" will silently produce duplicates when
  the underlying expression changes.

### 2. Sort order depends on ephemeral content

`CANONICAL_ORDER` uses `text` as a tiebreaker. Within a single traversal of a
fixed expression, no two positions share the same `path` (a tree has exactly
one node per path), so `text` is never needed to break ties in practice.
Relying on it nevertheless couples ordering to string-form content.

### 3. Text as input to equality checks creates fragility

Test code already constructs `TreePosition` with literal text strings:

```java
new TreePosition(List.of(), "x + 1")
new TreePosition(List.of(0), "x")
```

Any change to the expression formatter (spacing, operator precedence
parentheses) would silently break equality checks without a compilation error.

## Alternatives considered

### A. Keep the current design (path + text as identity)

**Pro:** No migration required. `pathKey()` and `text` are useful for display
without a separate lookup.  
**Con:** Identity is fragile across rewrites. Ordering depends on content.
Positions at different expression states cannot be cross-referenced by
structural location.

### B. Remove text from identity; keep it as a field for display

```java
public record TreePosition(List<Integer> path, String text) { … }
```
Override `equals`/`hashCode` manually to use only `path`. Keep `text` for
`toString` and display.

**Pro:** No API change. Text still carried without a separate lookup.  
**Con:** Manual overrides of auto-generated `record` methods are unusual in
Java and easy to miss in code review. The `text` field remains in the
constructor, which implies it participates in identity to readers.

### C. Split into path-only identity + separate snapshot type (recommended)

```java
// Structural identity — path only
public record TreePosition(List<Integer> path) { … }

// Ephemeral snapshot, created during traversal
public record PositionedSubtree(TreePosition position, String text) { … }
```

`TreePosition` becomes a pure position key with stable identity across
rewrites. `PositionedSubtree` (or the existing package-private
`MoveExpressions.PositionedExpr`) carries the text at traversal time.

**Pro:**
- `TreePosition.equals` / `hashCode` work correctly across expression states.
- Ordering does not depend on content.
- Future `LocalRewrite` can store `TreePosition` as a stable reference to
  "where the rewrite was applied" without encoding the pre-rewrite text.
- The semantic layer can annotate any `TreePosition` independently of which
  expression was current when the position was first seen.

**Con:**
- API break: call-sites that currently access `position.text()` need to obtain
  the text separately (from `PositionedSubtree` or by navigating the tree).
- The only current call-site is package-private
  (`TreeLocalMoveEnumerator.enumerateTree`), so the migration scope is small.

## Recommended design

**Option C.**

`TreePosition(List<Integer> path)` is the structural identity.  
`PositionedSubtree(TreePosition, String text)` is the ephemeral traversal view.

`CANONICAL_ORDER` on `TreePosition` sorts by path length first (shorter =
closer to root), then element-wise numerically. No text tiebreaker needed,
because within one traversal each path is unique.

`pathKey()` remains a display helper returning `"root"` for the empty path and
`"000.001"` for depth-2 paths (zero-padded, dot-joined).

## Migration impact

| Item | Impact |
|------|--------|
| `TreePosition` record | Remove `text` component; adjust `CANONICAL_ORDER` to drop `thenComparing(text)` |
| `TreeLocalMoveEnumerator.enumerateTree` | Use `PositionedSubtree` locally; pass only `path` to `TreePosition` constructor |
| `LocalCandidateMove` | Unchanged (`position` field type stays `TreePosition`) |
| `TreeLocalMoveEnumeratorTest` | Construct `new TreePosition(List.of())` instead of `new TreePosition(List.of(), "x + 1")` |
| `MoveExpressions.PositionedExpr` | Can be promoted to `PositionedSubtree` or stay package-private |
| Future `LocalRewrite` | Can store `TreePosition` as a stable key without risk of stale text |

No other modules reference `TreePosition` today. The change is contained in
`de.regelsuche.moves.enumerate`.

## Decision deferred

The production-code change is intentionally not included in PR #129 to avoid
scope creep. This ADR records the decision so that the next PR that introduces
`LocalRewrite` can apply the migration as a prerequisite.
