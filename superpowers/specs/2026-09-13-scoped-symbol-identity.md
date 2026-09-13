# Scoped symbol identity: design and acceptance cases

This refines the user-approved implementation plan in
`docs/superpowers/plans/2026-09-13-symbol-identity.md`. It is a design, not a
claim that implementation, product migration or a performance study is complete.
The initial PRs #1001 and #1002 are already contained, with their ancestry, in
integration #1003. Merge its qualified current head before production changes.
Do not move main, relax protection, or restart protected studies to achieve that.

## Identity, occurrences and presentation

Use `SymbolId` with an allocation namespace UUID and a positive local ordinal.
Allocate a namespace once, not once per occurrence. Explicit namespace injection
makes test inputs reproducible; snapshots retain it for replay. No display name
is part of ID equality or hashing. IDs identify symbols, not their mathematical
values: distinct symbols can have equal values under an assumption.

`SymbolScope` resolves input names and aliases. Explicit local declaration may
shadow an inherited binding; ordinary resolution reuses the inherited symbol.
Limits apply to names, declarations and child namespaces. Snapshot restoration
preserves the allocation high-water mark and rejects inconsistent local IDs,
parent identity, duplicate namespaces and capacity violations. Restoring a
snapshot is a single-writer resume operation, not distributed allocation:
independently active copies must use fresh namespaces for new symbols.

Every parsed occurrence remains a distinct syntax node with its own original
source range. The existing `ExprValueFactory` remains the bounded, owner-scoped
value interner. Equal scoped values share one value object in that factory;
clearing, closing or using a different factory does not create a global `==`
guarantee. Stable ID/value equality works across those boundaries.

Presentation belongs to a separate immutable symbolic document. Renaming a
label does not alter its scoped expression, IDs, mathematical value key, source
bindings or original numeric evidence. Labels used for textual output must be
valid, unambiguous identifiers in that document. Different documents/scopes may
use the same label for different symbols. Aliases may identify the same symbol.

## Existing AST and text-engine integration

Keep `VariableExpr` as the existing AST variable kind; do not add a competing
expression hierarchy. Add a typed scoped creation path and symbol accessor.
Keep its ordinary string constructor and existing name-based inputs as the
legacy contract. In the new path, `name()` is an identity transport identifier,
not a display label. A reserved, canonical identifier encodes the complete ID
for existing formatters, normalizers, string-based engines and e-graph leaves.
Reconstruction must recover exactly that ID, never allocate a fresh symbol.
Malformed reserved identities must not silently become another valid symbol.
The reserved namespace and its difference from ordinary user names are explicit.

Do not overload unrelated reference-type constructors in a way that makes
existing null-argument calls ambiguous. Retain the legacy AST JSON shape and
ordinary diagnostic text. Scoped JSON uses identity transport, not labels.

Historical unscoped value keys retain v2 bytes. Scoped variables use v3 keys;
compound keys containing scoped operands propagate v3. Numerical semantics,
operator laws and existing simplification/verification work are unchanged.
Alpha-renaming equivalence remains a separate operation from symbol identity.

A `SymbolicExpression` keeps the original `ExactParsedTerm` and a separate,
identity-bound AST projection. It does not relabel parser certificates as though
the scoped projection had originally appeared in the source. Numeric leaf
provenance remains attached to the actual original numeric occurrence. Limits
must be checked before unbounded recursion or scope allocation. Invalid input,
missing bindings and capacity failures must not partially allocate symbols.

## Persistence and matching

Use a bounded versioned JSON document containing the original source, complete
source-name-to-ID bindings and ID-to-display-label mappings. Reparse the original
source when loading; require exact binding coverage and reject duplicate or
unknown fields, trailing JSON, malformed UTF-8, noncanonical IDs, unused/missing
bindings, missing labels and ambiguous labels. This document is neither proof
nor production authorization. Do not infer trust from a UUID or a hash.

Add parsed-AST entry points to the existing `RulePatternMatcher`, preserving its
current string entry points and repeated-placeholder consistency checks. Test
existing e-graph insertion/extraction; do not create another matcher or search
engine. Symbol identity alone does not repair the rule-ID-only strategy dispatch
summary. Cross-step binding selection is a separate remaining optimization.

## Executable acceptance matrix

1. Canonical ID round trips; different namespaces/ordinals; malformed, overlong,
   noncanonical and zero/negative ordinal controls.
2. Repeated name resolution returns one ID; aliases reuse it; local declaration
   shadows parent scope; child namespace collisions and invalid names fail.
3. Allocation snapshots resume without reuse. Malformed snapshots and a batch
   that exceeds capacity leave the existing scope unchanged.
4. Scoped AST variables compare by ID, not occurrence or label. Ordinary AST
   construction, JSON and v2 keys retain their historical behavior.
5. A repeated scoped variable interns once; another scope with the same display
   label remains distinct. Independent factories have equal keys but need not
   have the same Java object. Mixed compound keys retain the scoped revision.
6. `(x+y)*(x-y)+y^2` matches the shared-placeholders pattern and the corresponding
   `+z^2` expression does not. Aliases, renamed labels and composed placeholders
   exercise the same real matcher rather than a test-only substitute.
7. Original source spans distinguish all repeated variable occurrences. Numeric
   provenance survives projection, display rename and document round trips.
8. Actual existing polynomial normalization, formatting, reparsing and e-graph
   extraction retain the symbol IDs. No result is accepted solely by its label.
9. Strict document decoding rejects missing/unused bindings, duplicate/unknown
   fields, trailing values, invalid UTF-8, malformed IDs and ambiguous labels.
10. Run unchanged core tests, focused learning/e-graph integration and negative
    controls before publishing. Read back the resulting repository tree.

## Qualification boundary

The first delivery is the explicit scoped Java input/document path, not a silent
migration of all CLI, HTTP, persisted release or proof contracts. A default
product migration requires its own versioned input/replay policy. No speedup is
claimed merely from introducing UUIDs or sharing objects.

Local verification must state the actual JVM and compiled scope. In the current
local environment Java 21 cannot compile the unchanged Java-25 `ScopedValue`
cache class; exclude that class only from the supplementary local source
compilation, not from committed build files or CI. The full original Java-25
Gradle/Maven/Docker, JMH, SymPy, coverage and code-scanning checks remain required.
