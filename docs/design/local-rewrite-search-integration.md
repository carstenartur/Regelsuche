# Local Rewrite Search Integration

- Date: 2026-06-14
- Related: #105, #106, search-space exploration

## Core successor shape

```
Search state
  -> root expression
  -> TreeLocalMoveEnumerator
  -> LocalCandidateMove(TreePosition, CandidateMove)
  -> LocalRewriteApplier
  -> LocalRewriteResult.expressionAfter
  -> Search successor
```

## Minimal search loop

1. Parse or receive the current expression state.
2. Enumerate local candidates for every subtree.
3. For each candidate group, call `LocalRewriteApplier.apply`.
4. Drop failed results.
5. Canonicalize or normalize `expressionAfter` for duplicate detection.
6. Add a directed search edge carrying:
   - `beforeRoot`
   - `afterRoot`
   - `position`
   - `move kind`
   - `bindings`
   - `subtreeBefore`
   - `subtreeAfter`

## Why this is enough for search expansion

`TreePosition + LocalCandidateMove` describes where and what to try. `LocalRewriteApplier` turns that into a concrete next expression without depending on UI code. Search can therefore use the same primitive as Rule Inspection and future Discovery Visualization.

## Required adapter

Search currently consumes `Transformation` and move metadata in several places. A thin adapter can map `LocalRewriteResult` to a search edge or `Transformation`:

| Local rewrite field | Search field |
|---|---|
| `originalExpression` | source state |
| `expressionAfter` | successor state |
| `kind` | rule/move category |
| `bindings` | edge parameters |
| `position.pathKey()` | affected position |
| `subtreeBefore` / `subtreeAfter` | explanation/highlight payload |

## Open points

- Add a generic candidate grouping key so multi-parameter logical matches are not UI-specific.
- Decide whether search should use `TreePosition(path)` directly or a richer immutable rewrite event.
- Promote stale-position checks into a reusable precondition model for concurrent UI/search use.
