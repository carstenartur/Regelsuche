# Local Rewrite Semantic Layer Readiness

- Date: 2026-06-14
- Target consumers: explanation layer, tutor mode, accessibility, game mode

## Required semantic event

A local rewrite can be represented as:

| Field | Meaning | Current source |
|---|---|---|
| `beforeRoot` | full expression before rewrite | `LocalRewriteResult.originalExpression` |
| `afterRoot` | full expression after rewrite | `LocalRewriteResult.expressionAfter` |
| `beforeSubtree` | matched subtree before rewrite | `LocalRewriteResult.subtreeBefore` |
| `afterSubtree` | rewritten subtree | `LocalRewriteResult.subtreeAfter` |
| `position` | structural address of changed subtree | `LocalRewriteResult.position` |
| `move` | rule/move identity | `LocalRewriteResult.kind` plus candidate metadata |
| `bindings` | generated or matched parameters | `LocalRewriteResult.bindings` |

## Example narration

The current data is enough to later produce:

> At position `000`, completing the square was applied.

The same event can also support richer narration:

> The subtree `x ^ 2 + 6 * x + 5` at position `000` was rewritten to `(x + 3) ^ 2 - 4` using `shift = 3` and `residue = -4`.

## Readiness assessment

The necessary raw fields are present, but they are not yet packaged as a first-class semantic event. `LocalRewriteResult` is close to that event shape and can be promoted or adapted without changing the applier.

## Open points

1. Add human-readable rule labels separate from enum names.
2. Preserve stable move ids from `RewriteMove` in local rewrite results.
3. Add localization-ready message templates for accessibility and tutor mode.
4. Store both path-only identity and display text so UI wording does not define identity.
5. Include proof/validation status when oracle integration is available.
