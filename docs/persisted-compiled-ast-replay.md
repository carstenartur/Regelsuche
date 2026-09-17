# Persisted compiled AST replay

`CompiledAstReplayCodec` stores the complete typed candidate histories introduced
in #1017. This is the persisted-replay boundary of #1010; it does not migrate
the general search or the live learned dispatcher to typed states.

## Save, reload and check

```java
var codec = new CompiledAstReplayCodec();
var typed = new CompiledLinearRewriteEngine(existingProgram, 128).compileAst();
var candidate = typed.transformMeasured(sourceAst).candidates().getFirst();
byte[] document = codec.encode(candidate);
Files.write(path, document);

// Receiving code supplies its own program and expected source. Neither comes
// from a trusted-execution claim made by the file.
var receiving = new CompiledLinearRewriteEngine(existingProgram, 128).compileAst();
var replay = receiving.replayEncoded(sourceAst, Files.readAllBytes(path));
Expr target = replay.target();
var regenerationWork = replay.workMetrics();
```

`decode(byte[])` alone returns **unverified data**. `replayEncoded(Expr, byte[])`
decodes it and invokes the existing full-program regeneration. Source-node IDs,
every intermediate tree, rule metadata and per-step side conditions must match.
Equal endpoints or an unchanged aggregate set of conditions are not enough.
The receiving rules can still be custom rules: regeneration proves execution
relative to those rules, not their mathematical correctness or the truth of
side conditions. Imported data never selects Java classes or installs rules.

## Representation and identity

The UTF-8 JSON schema is `regelsuche.compiled-ast-replay/v1`, bound to the
current `regelsuche.compiled-linear-rewrite/ast-v1` backend. It retains the
program ID, ordered source IDs, all states, and metadata for each step. Adjacent
steps refer to the same retained state rather than repeating independent source
and target fields that could disagree.

AST nodes have explicit tags for `number`, `variable`, `symbol`, `binary` and
`function`. Binary children and function arguments are ordered. A number stores
normalized exact rational **value text** interpreted by `ExactRational`, never
by the expression parser: a `NumberExpr(1/3)` remains one node, not a DIV tree.
A scoped variable stores its canonical `SymbolId`, separately from legacy names.
Aliases sharing an ID remain the same mathematical symbol; labels are not
invented or used as identities. Original spelling and display labels are outside
this execution-history format.

`contentHash(candidate)` is SHA-256 over deterministic encoded bytes. It binds
source, every intermediate state, metadata and side conditions. It is **not** a
signature, authorization, complete rule-set fingerprint or proof. Program IDs
are retained labels, not a claim that every receiving rule definition is identical.
A saved record is accepted only after regeneration against the supplied program.
The archive does not store allegedly authoritative work counters; actual replay
returns the receiving program's measured regeneration work.

## Input contract

Both encoding and decoding enforce the following interchange limits:

- 1 MiB UTF-8 per document and 4,096 characters per scalar string.
- One through eight steps, matching source-ID count, and two through nine states.
- 10,000 AST nodes and depth 128 per state; at most 128 conditions per step.

These are serialization limits, not changes to legacy execution or CPU/allocation
quotas. In-memory custom rules may produce values outside this interchange envelope.
Unrecognized schemas/backends/node tags, missing or additional fields, duplicate
JSON keys, trailing documents, invalid UTF-8 or unpaired Unicode surrogates are
rejected. Booleans and integer metadata are not coerced from strings or floats.
Numeric value text and condition lists must already be canonical. Oversized
output fails rather than returning partial JSON. No Java serialization, class
loading, expression-text fallback or implicit mathematical normalization occurs.

## Verification

The new test API was first compiled against the unchanged #1017 source and failed
because the codec and `replayEncoded` method did not exist. This is an absent-API
check, not a claim of an existing runtime regression. Twenty-one new tests now
cover round trips, filesystem reload with fresh instances, actual replay,
metadata/condition/intermediate-state tampering, malformed documents and exact
limits. Integration uses the actual evolution compiler: reloaded TRAIN histories
form the existing binding model, and reloaded application histories preserve
grouped compounds, rational leaves and scoped symbols. Valid primitive paths at
the wrong occurrence are still rejected by the learned binding check.

All 166 search production sources and 71 search/new-integration test sources were
freshly compiled locally with Java 25. A run of 1,201 core/search/new-integration
tests passes under a UTF-8 system locale. A preliminary run exposed three existing
filename-test errors with an ASCII native encoding; switching only the local
locale to UTF-8 resolves them. No test or production code was changed for this.
The broader local learning-module run exceeded the 45-second invocation limit;
it is not reported as a successful full local build.

Two isolated mutations were tested: skipping regeneration fails four tests;
reparsing numeric value text as an expression fails three. Removing both overlays
restores the focused checks. These are targeted sensitivity checks, not complete
mutation coverage. Exact-head Maven and ordinary product CI results belong to
the PR discussion and remain distinct from this local verification.

General typed search, live dispatcher collection/backend selection and global
work-budget integration remain open under #1010. Existing string replay, frozen
studies, learned-policy identities, thresholds and defaults are unchanged. No
new performance or learning-superiority result is claimed by this persistence work.
