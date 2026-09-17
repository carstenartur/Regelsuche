# Persisted compiled AST replay implementation plan

> Execute the approved #1010 transport migration one tested boundary at a time.

## Contract

Persist complete `CompiledAstRewriteProgram.Candidate` histories as versioned,
strict UTF-8 JSON data. Ordered AST structure, exact rational leaves, scoped IDs,
source-node IDs, metadata and normalized per-step conditions survive a fresh
codec instance. A decoded document is not authorized: `replayEncoded` must call
the existing full-program regeneration against an explicitly supplied source.
No rules or executable classes are imported. Byte hashes identify content, not
truth, signatures, engine definitions or proof authority.

Use a tagged AST representation, not infix display text or Java serialization.
Keep data parsing and replay separate. Unrecognized versions/fields, duplicate
keys, wrong JSON types, malformed Unicode, noncanonical rational literals,
inconsistent lengths and oversized inputs must fail before execution.
Limits: 1 MiB UTF-8 document; 4,096 characters per scalar string; 10,000 nodes
and depth 128 per state; 1–8 steps, 2–9 states; at most 128 side conditions per
step. Existing in-memory execution can exceed these serialization limits.

## Tasks

- [x] Add round-trip, real compiled replay, mutation and strict-decoding tests.
      First compile against the current source to establish the absent API.
- [x] Implement package-private tagged-AST JSON handling and the public
      `CompiledAstReplayCodec.encode/decode/contentHash` facade in search.
- [x] Add `CompiledAstRewriteProgram.replayEncoded(Expr, byte[])` delegating to
      full regeneration; no endpoint-only shortcut or old-parser fallback.
- [x] Exercise actual evolution-compiled learning traces after save/reload.
- [x] Verify sensitivity by removing regeneration and by losing AST grouping.
- [ ] Run focused tests, whole available modules, source-pinned Maven qualification
      and review. Preserve incomplete/failing evidence rather than relabel it.

## Remaining boundaries

This implements persisted replay of the compiled typed paths, not general typed
search, live dispatcher collection/backend selection, automatic strategy
promotion or end-to-end work budgets. #1010 remains open. Legacy string code,
frozen studies, policy identities, thresholds, dependencies and default behavior
remain unchanged. No speedup claim follows from a serialization test.
