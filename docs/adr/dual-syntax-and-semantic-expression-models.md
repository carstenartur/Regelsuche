# ADR: Define mathematical expression identity

- Status: Accepted
- Date: 2026-07-12
- Decision date: 2026-07-12
- Related: PR #241, `ExpressionCanonicalizer`, `BinaryExpr`, `TreePosition`,
  `TermOccurrenceIndex`, equality saturation
- Evidence: `docs/architecture/expression-identity-inventory.md`,
  `docs/architecture/expression-identity-benchmark-results.md`

## Decision

Regelsuche adopts **layered expression identity**.

The project will distinguish four kinds of identity and will not encode all four in
one Java object:

1. **Syntax/notation identity** — how an expression was written or generated,
   including grouping, operand order and source position.
2. **Occurrence/use identity** — one concrete use of an expression inside a syntax
   root or derivation step.
3. **Mathematical value identity** — the immutable expression value under the
   explicitly declared laws of the active domain.
4. **Equivalence-class identity** — broader, dynamic equivalence under a selected
   rewrite theory and assumption context.

The selected representation is:

> **The current syntax AST remains the occurrence and notation representation,
> while a separate, immutable and scoped-interned mathematical value DAG is added.
> A typed occurrence index links syntax occurrences to value nodes. E-classes remain
> a broader equivalence layer above ordinary value identity.**

This is representation alternative E from the proposal, with one important
migration refinement: Regelsuche does not introduce a second independent semantic
*tree*. It introduces shared value nodes and evolves the existing occurrence-index
concept into the linking layer.

## Exact identity contracts

### Current `Expr`

During migration, `Expr` continues to denote the existing syntax-shaped node.

- `Expr.equals` remains structural.
- Binary grouping and child order remain part of that equality.
- Existing constructors remain available.
- Existing exact rules, visitors and local rewrites continue to operate on `Expr`.
- `Expr` is not globally interned.

This is required because current code already uses Java reference identity of parsed
`Expr` nodes as process-local occurrence identity. In particular,
`AstVisitorContext` stores metadata in an `IdentityHashMap<Expr, ...>`.

### Mathematical value

A new value abstraction, provisionally named `ExprValue`, denotes an immutable
mathematical expression value.

Its equality and stable key:

- exclude source spans, display order, grouping and occurrence IDs;
- apply only laws explicitly declared for the operator and domain;
- preserve multiplicity unless the domain explicitly declares idempotence;
- remain stable across factory scopes and serialization;
- do not depend on collection iteration order.

For ordinary associative and commutative addition:

```java
sameValue(sum(a, b, c), sum(c, a, b));
sameValue(sum(sum(a, b), c), sum(a, sum(b, c)));
!sameValue(sum(a, a, b), sum(a, b));
```

Subtraction, division and other non-commutative operators retain operand roles and
order.

### Factory-scoped reference identity

A bounded owner supplies an `ExprValueFactory`. Within that factory scope, equal
canonical values are interned and may use reference equality as a fast path:

```java
ExprValue a1 = factory.variable("a");
ExprValue a2 = factory.variable("a");
assert a1 == a2;
```

The scope is explicit, for example one parse bundle, search session, compilation
unit or another bounded lifecycle. This ADR rejects an unbounded global intern pool.

Reference identity is an optimization and sharing guarantee inside one scope. It is
not the persisted definition of mathematical equality. A stable structural
`ValueKey` remains authoritative across scopes and deserialization.

### Occurrence/use identity

A concrete occurrence has its own identity independent of the value it references.
Conceptually:

```java
record ExprOccurrence(
    OccurrenceId id,
    TreePosition position,
    Expr syntax,
    ExprValue value
) {}
```

The final API may differ, but it must support:

- two occurrences referencing the same value;
- local replacement of one occurrence without replacing all uses;
- forward lookup from syntax occurrence to value;
- reverse lookup from value to all occurrences in the owned root;
- source highlighting, provenance and parent/operand roles.

A mathematical value has no single mutable `parent()` and no single tree position.
Parent relationships belong to occurrences or edges.

## Java collection decision

This ADR does **not** reject Java `Set` as a general representation.

A normal

```java
Set<ExprOccurrence>
```

is appropriate for an unordered collection of occurrences when occurrence identity
is explicit. Two occurrences may have different IDs while both reference the same
interned `ExprValue`.

A bare

```java
Set<ExprValue>
```

cannot by itself represent general addition because value equality would collapse
`a + a + b` to the same members as `a + b`.

The mathematical contract of an AC operation must preserve multiplicity. Its
internal representation may be any immutable structure satisfying that contract,
including:

- `Map<ExprValue, Integer>` multiplicities;
- a multiset;
- a coefficient map where algebraically valid;
- a set of explicit use/edge objects plus value-independent semantic equality.

The public semantic contract is more important than the chosen Java collection.

## Why this decision was selected

### Existing architecture already contains the required pieces

The inventory found several partial forms of the selected architecture:

- `TreePosition` identifies a concrete subtree within a syntax root.
- `AstVisitorContext` distinguishes equal syntax nodes by reference.
- `TermOccurrenceIndex` already stores every occurrence separately while grouping
  by a provisional canonical string value.
- `ExpressionCanonicalizer` already provides bounded mathematical keys used across
  search and mining.
- `EGraph` already hash-conses e-nodes and keeps broader equivalence and parent
  relationships outside the syntax AST.

The selected design aligns these concepts instead of adding a second unrelated
semantic tree and a new manually synchronized projection subsystem.

### Functional spike results

The bounded addition/repeated-subexpression spike established:

- AC-equivalent syntax trees can share one value identity.
- Multiplicity remains significant.
- A normal set preserves two uses of one shared value when its elements are
  occurrences.
- One value can have several concrete paths and parent contexts.
- A local rewrite remains occurrence-based.
- Stable structural keys preserve equality across factory scopes.
- A pure evaluator can memoize each shared value once.

For the AC corpus `(a+b)+c`, `a+(b+c)` and `c+a+b`:

- concrete syntax occurrences: 15;
- non-interned semantic value allocations: 15;
- distinct scoped-interned values: 7.

For `(a+b)*(a+b)`:

- concrete occurrences: 7;
- distinct values: 4 (`a`, `b`, `a+b`, and the product).

### Performance evidence

JMH run 479 measured the prototype on Temurin 21.0.11:

| Operation | Mean |
|---|---:|
| Non-interned value projection, 256-expression corpus | 77.224 µs/op |
| Interned projection, fresh scope | 115.989 µs/op |
| Interned projection, warm scope | 110.636 µs/op |
| Repeated-subexpression tree evaluation | 1.23194 µs/op |
| Memoized value-DAG evaluation | 0.178929 µs/op |

The prototype's interning construction was approximately 1.43–1.50× slower than
plain semantic allocation. The DAG evaluator was approximately 6.88× faster on the
intentionally repeated pure-expression corpus.

Therefore the decision is **not** to rebuild or re-intern values inside every rule
match. The value graph is built once per bounded owner and reused. Construction
cost is accepted only where subsequent search, matching, analysis or evaluation can
amortize it.

### Cognitive-cost comparison

A permanent dual-AST design requires ordinary code to distinguish and synchronize:

- syntax nodes;
- semantic-tree nodes;
- projection links;
- source occurrences;
- e-classes.

The selected design reuses existing concepts:

- syntax AST for notation and local positions;
- an occurrence index evolved from `TermOccurrenceIndex`;
- shared values for canonical identity and caches;
- e-classes only for broader equivalence.

Ordinary syntax rules can remain unaware of the value layer. Value-aware rules and
performance-sensitive services opt into a focused facade rather than maintaining
links manually.

## Relationship to PR #241

PR #241's recognition profiles remain valid and are not replaced wholesale.

A canonical value layer may later supply the AC-normalized inputs and stable keys
used by recognition. PR #241 remains responsible for concerns beyond ordinary value
identity, including:

- exact versus broadened recognition policy;
- syntax-sensitive patterns;
- bounded algebraic binding inference;
- allow-listed equivalence providers;
- anti-unification across broader representations.

The recognition normalizer should eventually delegate shared AC/value logic rather
than maintain a competing canonicalization implementation.

## Relationship to the e-graph

An `ExprValue` is one immutable canonical value representation under the ordinary,
bounded value laws.

An e-class represents one or more values/representations proven equivalent under a
selected rewrite theory, assumptions and saturation budget. E-class identity can
change after union/rebuild and therefore is not the ordinary identity of
`ExprValue`.

The boundary is:

```text
syntax occurrence -> ExprValue -> EClassId
```

The e-graph may reuse `ValueKey` or value children, but it does not replace concrete
occurrence identity.

## Relationship to compiled execution

Pure analysis, evaluators, method handles or generated bytecode may use
`ExprValue` as a cache key. Shared value nodes expose common subexpressions without
requiring a separate compiler-only CSE pass.

Mutable runtime state, tracing and explanation metadata must not be attached to a
shared value. Those remain execution- or occurrence-specific.

This ADR permits compiled execution; it does not require a bytecode library or a
production compiler.

## Rejected alternatives

### Keep only the binary syntax AST

Rejected as the long-term identity model because mathematical identity remains
fragmented among canonical strings, matchers, search hashes and e-classes. The
syntax AST remains, but it is no longer expected to carry mathematical value
identity by itself.

### Replace `Expr` with a semantic-only AST

Rejected because Regelsuche needs original notation, concrete occurrences,
didactic steps, local paths and syntax-sensitive rules.

### Two independent ASTs plus a general projection graph

Rejected as the primary architecture. It is mathematically viable but duplicates
equal semantic subexpressions, introduces another complete hierarchy and creates a
larger synchronization surface than necessary.

A projection *view* or adapter may still exist during migration, but the semantic
side is a shared value DAG rather than an independently owned tree.

### Canonical strings or hashes only

Rejected as the complete solution because keys do not expose typed substructure,
use relationships or a suitable compilation/cache graph. Canonical strings remain
compatibility and persistence tools.

### E-graph as the primary ordinary expression model

Rejected because e-class identity is dynamic, theory- and assumption-dependent, and
contains multiple representations. Equality saturation remains an optional broader
reasoning layer.

### Global interning

Rejected because an unbounded global pool creates retention, concurrency and test
isolation risks. Interning must have an explicit owner and lifecycle.

## Consequences

### Positive

- AC value identity no longer encodes meaningless grouping or order.
- Equal subexpressions can share analyses, rule results and compiled artifacts.
- Concrete occurrences remain independently selectable and explainable.
- Search and transposition keys can migrate from formatted strings to typed stable
  value keys without breaking existing APIs immediately.
- The design fits the current e-graph and occurrence-index direction.

### Costs

- A new value hierarchy and factory must be maintained.
- Every operator needs an explicit law/normalization policy.
- Value construction adds measurable overhead and must be cached or amortized.
- Persistence must store stable keys/structure and re-intern on load.
- Debugging tools must display both value identity and occurrence paths when needed.

## Implementation sequence

The architecture is accepted; production migration proceeds in separate changes.

### Phase 1: Core value model

Introduce in `regelsuche-core`:

- immutable `ExprValue` variants;
- `ValueKey` with deterministic serialization/hashing;
- `ExprValueFactory` with explicit bounded scope;
- operator-law metadata;
- AC sum/product nodes with multiplicity-preserving semantics;
- adapters from existing `Expr`.

Do not change `Expr.equals` or remove existing constructors.

### Phase 2: Typed occurrence index

Evolve the existing occurrence concepts rather than adding a parallel general graph:

- add explicit `OccurrenceId`;
- associate `TreePosition`/source information with `ExprValue`;
- provide forward and reverse lookup;
- preserve compatibility with `TermOccurrenceIndex` and local rewrites;
- migrate plugin metadata from raw `Expr` reference identity when an occurrence
  context is available.

### Phase 3: Canonicalization and search adapters

- derive stable value hashes from `ValueKey`;
- keep `ExpressionCanonicalizer` compatibility during migration;
- build one value graph per search/session and reuse it;
- migrate transposition and analysis caches incrementally;
- measure construction and retained-memory costs on representative searches.

### Phase 4: Recognition and e-graph integration

- let recognition reuse value normalization where profiles permit it;
- retain syntax-sensitive and broader-equivalence matching paths;
- map `ExprValue` into e-nodes without conflating value and e-class identity;
- preserve assumption fingerprints.

### Phase 5: Optional compiled execution

Prototype evaluator/matcher caches keyed by `ExprValue`. Adopt generated bytecode or
method handles only when a separate benchmark demonstrates a production benefit.

## Required safeguards

Production implementation must include tests for:

- AC equality independent of grouping, order and construction order;
- multiplicity preservation;
- non-commutative operator roles;
- two occurrences referencing one value;
- local replacement of one occurrence;
- stable keys across factory scopes and serialization;
- bounded factory lifecycle and release;
- no occurrence metadata stored on shared values;
- compatibility with assumption-aware identity;
- deterministic formatting that does not define equality.

## Non-goals

This ADR does not decide:

- that every operator is associative or commutative;
- the final class names or package layout;
- one mandatory Java collection implementation for sums;
- a bytecode-generation library;
- immediate replacement of `ExpressionCanonicalizer`;
- immediate migration of all existing rules;
- replacement of equality saturation.

Those are implementation or follow-up decisions constrained by the identity model
accepted here.
