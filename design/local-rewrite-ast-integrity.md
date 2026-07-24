# Local Rewrite AST Integrity

- Date: 2026-06-14
- Scope: `LocalRewriteApplier`

## Verified rewrite flow

```
Root expression string
  -> ExpressionParser.parseTerm
  -> Root AST
  -> subtreeAt(root, TreePosition.path)
  -> MoveRealizer on formatted subtree boundary
  -> parse realized subtreeAfter
  -> replacement subtree AST
  -> replaceAt(root, TreePosition.path, replacement)
  -> New Root AST
  -> ExpressionFormatter.format
  -> expressionAfter
```

## Integrity observations

- The full input is parsed into an `Expr` before any local rewrite is applied.
- Position lookup walks child indices from `TreePosition.path()`.
- Replacement rebuilds immutable parent AST nodes on the way back to the root.
- `expressionAfter` is produced by formatting the new root AST.
- String handling is confined to parser/formatter and `MoveRealizer` boundaries.

## Negative checks

`LocalRewriteApplier` does not perform local rewrite by:

- regex replacement
- `String.replace` / `replaceAll` / `replaceFirst`
- string slicing
- manual concatenation of prefix + rewritten subtree + suffix

Some other move/search utilities normalize whitespace with `replaceAll("\\s+", ...)`; those usages are canonicalization/reporting fallbacks and are not local subtree replacement.

## Roundtrip expectation

Every successful local rewrite should satisfy:

```
before
  -> rewrite
  -> expressionAfter
  -> parse
  -> render
  -> parse
  -> structurally equivalent AST
```

The dedicated `LocalRewriteApplierTest` roundtrip assertions cover the current successful root and nested local rewrite cases.

## Conclusion

Local rewrites are AST-based at the applier boundary. The current design is suitable as a foundation for highlighting, replay, successor generation, and semantic narration because the changed subtree is represented by structural path and AST replacement, not textual patching.
