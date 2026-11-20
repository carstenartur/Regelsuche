# Source-bound visual formula editing

## User outcome

Issue #669 section E requires text and visual formula entry to round-trip through the same parser/AST. A user can select a displayed subexpression, replace it, and use the resulting expression in the ordinary search form. The visible preview and the search payload must describe the same parsed expression.

## Design

Extend the existing formula preview response with optional parser-issued half-open UTF-16 source ranges. Resolve repeated equal nodes by identity using `ExactParsedTerm`; parser-generated unary zero has no editable source. For equations, derive offsets from the real parser's handling of the original source, with no independent grammar or canonical serialization shortcut.

Make rendered subexpressions selectable and provide an equivalent native keyboard control with clear labels and selected state. Editing replaces the selected original source slice, grouping a replacement where required to preserve enclosing precedence. The server's existing formula inspection endpoint validates the complete proposed expression before committing it to the main input. Invalid replacements leave the input unchanged. Preserve exact spelling and spacing outside the selected slice, including `0.50` versus `1/2`.

An edit of the source, input type or runtime profile invalidates the selection and outstanding responses. Stale preview or edit responses must never overwrite newer input. Keep the existing maximum source length and runtime-profile boundaries. The UI should explain selection, editing, validation and failures in ordinary user language.

## Verification and documentation

Use real parser/API tests for source ranges, repeated nodes, equations, grouping and synthetic nodes. In real browser tests, edit the second `x` in `f(x, x) = -(0.50)^2` using the keyboard-accessible control; verify unchanged source outside that occurrence, the exact resulting AST and actual search request. Cover invalid replacement, text round-trip and delayed responses after source/type/profile changes. Verify a narrow viewport and long expression for usable layout. Update OpenAPI and the user-facing Workbench guide; keep REST contract details in OpenAPI. Capture an actual useful GUI screenshot if the completed UI improves the demo gallery. No third-party editor dependency or separate AST/parser.

This completes the scoped formula-entry criterion; the remaining #669 cockpit requirements stay open.
