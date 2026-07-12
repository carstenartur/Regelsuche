# Expression identity inventory

This inventory supports the decision process in ADR #242. It classifies current
identity mechanisms without treating any of them as the final architecture.

## Current baseline

### `Expr` records: structural representation identity

`BinaryExpr`, `VariableExpr`, `NumberExpr` and related records use Java record
`equals` and `hashCode`. For `BinaryExpr`, child order and binary grouping therefore
participate in equality.

Consequences:

- `(a + b) + c` is not equal to `a + (b + c)`;
- `a + b` is not equal to `b + a`;
- two independently allocated `VariableExpr("a")` values are equal;
- `Expr.equals` cannot reveal whether two equal children are the same object or two
  separate written occurrences.

Classification: **representation identity**, currently syntax-shaped.

### Parser allocation: occurrence-like objects without explicit occurrence IDs

`ExpressionParser` constructs nodes directly with `new` and folds additive and
multiplicative chains left-associatively. Repeated input such as `a + a` creates two
separate but equal `VariableExpr` objects.

Those allocations already carry occurrence meaning in some consumers, but no
stable occurrence ID or source span is part of `Expr`. Object identity is therefore
observable and useful inside one traversal, yet cannot safely be persisted or
reconstructed as the definition of occurrence identity.

Classification: **process-local occurrence identity by allocation**, currently an
implicit contract.

### `AstVisitorContext`: direct evidence that current `Expr` objects are occurrences

`AstVisitorContext` stores plugin metadata in an `IdentityHashMap<Expr, ...>`. Thus
the two equal `a` nodes in `a + a` can carry different metadata. Interning the
current `Expr` objects directly would collapse those entries and change plugin
behaviour.

Architectural implication: **the existing `Expr` type must not simply be converted
into an interned value type**. A new value identity has to be introduced alongside
an explicit occurrence/use identity, or all occurrence-sensitive consumers must be
migrated first.

Classification: **occurrence identity by Java reference**.

### `ExpressionCanonicalizer`: canonical mathematical key

The canonicalizer flattens associative additions and multiplications, groups equal
terms/factors, applies selected assumption-free reductions, chooses deterministic
output order and reconstructs a binary `Expr`. `stableHash` hashes the formatted
canonical result.

Consequences:

- AC variants collapse to one key;
- multiplicity remains significant;
- canonical order is deterministic but currently materialized as a binary tree;
- the relation from canonical terms back to concrete input occurrences is lost;
- assumption-aware hashes include an assumption fingerprint.

Classification: **bounded mathematical value identity used as a key**, not yet a
first-class value graph.

### Tree positions: occurrence/notation identity within one tree

`TreePosition` and local rewrite code identify a subtree by a child-index path and a
formatted-text staleness guard. Paths distinguish two equal occurrences inside one
concrete binary tree and are therefore essential for selecting one rewrite
location.

A path is meaningful only relative to a particular root representation. AC
canonicalization or DAG sharing invalidates the assumption that a mathematical
value has one unique path, but does not invalidate paths as identifiers of syntax
occurrences.

Classification: **occurrence identity relative to a concrete syntax root**.

### `TermOccurrenceIndex`: an existing value/occurrence split in embryonic form

`TermOccurrenceIndex` walks every syntax occurrence, records its path, role and
parent operator, and groups occurrences by a `canonicalValue` string. It already
supports questions such as “which composite value occurs at least twice?” while
retaining each concrete occurrence.

The current implementation uses formatted text for both `canonicalValue` and
`originalValue`; therefore the split is not yet backed by a mathematical value
object. Nevertheless, its shape closely matches the proposed use graph:

- `TermOccurrence` is an occurrence record;
- `canonicalValue` is a provisional value key;
- `countsByCanonical` is a multiplicity/index view;
- `path` preserves local-rewrite addressability.

Architectural implication: the chosen architecture can evolve this existing
concept rather than introducing an unrelated projection subsystem.

Classification: **occurrence index keyed by provisional string value identity**.

### Stable hashes in search and mining

`ExpressionCanonicalizer.stableHash` is used across transformation guards, search
strategies, convergence analysis, graph clustering, telemetry and mining. This is
currently the broadest shared notion of mathematical sameness.

Architectural implication: changing canonical identity affects substantially more
than parsing. A replacement needs an adapter and compatibility phase rather than an
immediate switch of `Expr.equals`.

Classification: **cross-subsystem mathematical key**.

### Direct construction footprint

`new BinaryExpr(...)` is used throughout parsing, canonicalization, calculus,
rules, local rewrites, equation/inequality solvers, mining and e-graph extraction.
A factory requirement cannot be imposed on the existing syntax type in one step
without a repository-wide migration.

Architectural implication: introduce a separate factory-created value layer first.
The current syntax constructors remain available during migration.

Classification: **migration constraint**.

### E-graph/equality saturation

`EGraph` already hash-conses `ENode` values and stores parent relationships
separately through e-classes. This demonstrates that shared values plus external
parent/use relations are compatible with the project.

However, e-class membership is dynamic, assumption-sensitive and dependent on the
selected rewrite theory. One e-class can contain several representations, and its
identity can change after `union` and `rebuild`. It is therefore not the ordinary
immutable value identity needed by parser, cache and local-rewrite code.

Classification: **bounded, dynamic equivalence-class identity**.

### PR #241 recognition profiles

PR #241 introduces explicit, bounded associative/commutative recognition and an
`EquivalentExpressionProvider`. It deliberately does not globally redefine
`Expr.equals`. Its `RecognitionNormalizer` flattens selected operators, sorts
commutative operands for deterministic rebuilding and produces another binary
`Expr`.

A future canonical value layer can replace some duplicated AC normalization and
provide stable value keys. PR #241 still remains useful for:

- exact versus broadened recognition policy;
- syntax-sensitive patterns;
- algebraic binding inference;
- bounded equivalence providers beyond ordinary value identity.

Classification: **rule-specific matching equivalence**.

## Current identity matrix

| Mechanism | Mathematical value | Representation | Occurrence/use | Notation | Stable across serialization |
|---|---:|---:|---:|---:|---:|
| `Expr.equals` | partially | yes | no | grouping/order only | yes |
| Java `==` on parsed `Expr` | no | allocation only | process-local yes | no | no |
| `AstVisitorContext` identity map | no | node reference | yes | indirectly | no |
| canonical string/hash | bounded yes | canonical representation | no | no | yes |
| `TreePosition` | no | relative to tree | yes, within one root | yes | only with same representation |
| `TermOccurrenceIndex` | formatted-string approximation | syntax subtree | yes | yes | yes, as strings/paths |
| e-class | theory-dependent yes | many representations | no | no | requires e-graph persistence/rebuild |
| PR #241 recognition | rule-dependent | matches variants | match-local | pattern-sensitive | profile is persistable |

## Confirmed gaps

1. There is no explicit type for immutable mathematical value identity.
2. There is no explicit occurrence/use type shared by parser, rewrite, plugin and
   explanation layers.
3. Parser object identity is already used as occurrence identity but is not a
   documented stable contract.
4. `Expr.equals` conflates semantic leaves with syntax-shaped composite identity.
5. Canonical identity has no bidirectional provenance to source occurrences.
6. Tree paths correctly identify syntax occurrences but cannot themselves describe
   multiple uses of one shared value.
7. `TermOccurrenceIndex` has the right separation but uses formatted strings rather
   than value objects.
8. E-class identity and ordinary canonical identity have no formally documented
   boundary.
9. No factory currently guarantees that equal mathematical values are the same Java
   object within a declared scope.

## Migration constraints established by the inventory

- Do not change the meaning of current `Expr.equals` during the first migration.
- Do not intern current syntax `Expr` objects directly; doing so breaks
  occurrence-sensitive identity maps.
- Keep stable hashes available for existing search and mining call sites.
- Introduce a separate immutable value type and a scoped factory.
- Preserve `TreePosition` for syntax-local rules while adding a value reference to
  occurrence records.
- Evolve `TermOccurrenceIndex` toward a typed use index instead of creating an
  unrelated, manually synchronized projection graph.
- Compare scoped interning with no interning; do not begin with an unbounded global
  pool.
- Require stable structural value keys even if `==` becomes a factory-scoped fast
  path.
- Keep e-classes as a broader equivalence layer above ordinary value identity.

## Executable characterization and spikes

`ExpressionIdentityCharacterizationTest` records the current behaviour:

- equal variables are structurally equal but not interned;
- repeated written variables are distinct allocations;
- plugin metadata distinguishes those allocations by reference;
- grouping and order affect `Expr.equals`;
- canonical hashes remove AC differences while retaining multiplicity;
- `BinaryExpr` permits a manually shared child, proving that the Java object graph
  can be a DAG even though the parser currently creates a tree;
- record equality cannot distinguish one shared child from two equal occurrences.

`ExpressionIdentitySpikeTest` compares the candidate architectures and establishes:

- both can implement AC equality;
- only scoped interning gives equal values one reference identity;
- a normal `Set<Occurrence>` preserves duplicate uses of one shared value;
- the value layer still needs multiplicity semantics independent of occurrence IDs;
- an interned DAG shares repeated subexpressions and supports occurrence-local
  replacement;
- stable structural keys preserve cross-scope and persistence identity;
- DAG evaluation can compute one repeated pure value once.

These tests are characterization and architecture evidence. They do not yet migrate
production rules.