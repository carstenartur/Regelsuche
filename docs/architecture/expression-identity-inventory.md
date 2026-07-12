# Expression identity inventory

This inventory records the current mechanisms and migration constraints used by ADR
#242.

## Current mechanisms

### Structural `Expr` identity

The AST node types are Java records. Therefore `BinaryExpr.equals` includes the
operator, child order and binary grouping.

- `(a+b)+c` differs from `a+(b+c)`.
- `a+b` differs from `b+a`.
- separately allocated `VariableExpr("a")` values are equal.
- structural equality cannot say whether equal children are one shared object or two
  written occurrences.

Classification: **syntax-shaped representation identity**.

### Parsed object identity

`ExpressionParser` constructs nodes directly. The two `a` nodes in `a+a` are equal
but distinct objects. This reference distinction is already observable:
`AstVisitorContext` stores metadata in an `IdentityHashMap<Expr, ...>`.

Consequently the existing `Expr` objects cannot simply be globally interned without
breaking occurrence-sensitive behavior.

Classification: **process-local occurrence identity by allocation**.

### Canonical mathematical key

`ExpressionCanonicalizer` flattens selected associative operations, groups equal
terms/factors, applies bounded reductions, chooses deterministic output and hashes
the formatted result.

- AC variants share a stable hash.
- multiplicity remains significant.
- assumptions participate in assumption-aware hashes.
- provenance back to written occurrences is lost.

Classification: **bounded mathematical value identity used as a cross-subsystem
key**.

### `TreePosition`

Local rewrite code identifies one syntax subtree by child-index path plus a formatted
staleness guard. It correctly distinguishes equal occurrences in one concrete root,
but a mathematical value can have several such paths.

Classification: **occurrence identity relative to a syntax root**.

### `TermOccurrenceIndex`

The index already walks every occurrence, stores path/role/parent metadata, and
groups occurrences by a provisional `canonicalValue` string. Its shape is close to
the accepted typed occurrence index:

- `TermOccurrence` is the occurrence record;
- `canonicalValue` is a provisional value key;
- `countsByCanonical` is a multiplicity/reverse-index view;
- `path` preserves local rewrite addressability.

Classification: **occurrence index keyed by provisional string value identity**.

### Direct constructor footprint

`new BinaryExpr(...)` is used throughout parsing, rules, calculus, solvers,
canonicalization, mining and extraction. Requiring a factory for the existing syntax
type would be a repository-wide migration.

Constraint: introduce a separate factory-created value layer first; keep syntax
constructors during migration.

### E-graph identity

`EGraph` already hash-conses e-nodes and maintains broader equivalence separately.
E-class identity is dynamic, theory-dependent and assumption-sensitive, so it is not
the ordinary immutable identity of one mathematical value.

Classification: **dynamic equivalence-class identity**.

### PR #241 recognition identity

PR #241 introduces bounded AC recognition, algebraic binding inference and
allow-listed equivalent-expression providers without redefining `Expr.equals`.
A future value layer can supply shared normalization, while recognition still owns
policy and broader matching.

Classification: **rule-specific matching equivalence**.

## Identity matrix

| Mechanism | Value | Representation | Occurrence | Notation | Persistent |
|---|---:|---:|---:|---:|---:|
| `Expr.equals` | partial | yes | no | grouping/order | yes |
| parsed `Expr ==` | no | allocation | process-local | no | no |
| `AstVisitorContext` identity map | no | node reference | yes | indirect | no |
| canonical string/hash | bounded yes | canonical form | no | no | yes |
| `TreePosition` | no | root-relative | yes | yes | with same representation |
| `TermOccurrenceIndex` | string approximation | subtree | yes | yes | yes |
| e-class | theory-dependent | many forms | no | no | rebuild/persist graph |
| PR #241 profile | rule-dependent | variant matching | match-local | pattern-sensitive | yes |

## Confirmed gaps

1. No explicit immutable mathematical value type exists.
2. No shared explicit occurrence ID spans parser, rewrite, plugin and explanation
   layers.
3. Parsed object identity is already used but is not a stable persisted contract.
4. Canonical identity has no bidirectional occurrence provenance.
5. `TermOccurrenceIndex` has the right separation but uses strings instead of typed
   values.
6. Ordinary value identity and e-class identity lack a documented boundary.
7. No bounded factory guarantees reference-identical equal values.

## Migration constraints

- Preserve current `Expr.equals` initially.
- Do not intern current syntax nodes directly.
- Keep canonical hashes as compatibility adapters.
- Add immutable values through a scoped factory.
- Preserve `TreePosition` for local syntax rewrites.
- Evolve `TermOccurrenceIndex` toward typed value references and explicit
  `OccurrenceId`.
- Keep e-classes above ordinary value identity.
- Persist stable structural keys; JVM identity is only a scoped fast path.

## Evidence

`ExpressionIdentityCharacterizationTest` records that:

- equal variables are not currently interned;
- repeated written variables are distinct occurrences;
- plugin metadata distinguishes those occurrences by reference;
- a normal `Set<Use>` retains two occurrence IDs that reference one value;
- grouping/order affect `Expr.equals` but canonical hashes remove AC differences;
- multiplicity remains significant;
- manually shared children already make the Java object graph a DAG.

A bounded temporary spike compared a duplicate semantic tree with scoped
hash-consing. The large temporary implementation was removed after measurement;
methodology, object counts and JMH run 479 are preserved in
`expression-identity-benchmark-results.md`.
