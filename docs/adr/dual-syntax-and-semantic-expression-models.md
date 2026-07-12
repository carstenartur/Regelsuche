# ADR: Mathematical expression identity

- Status: Accepted
- Date: 2026-07-12
- Related: PR #241, issue #243, `ExpressionCanonicalizer`, `TreePosition`,
  `TermOccurrenceIndex`, equality saturation
- Benchmark evidence: `docs/architecture/expression-identity-benchmark-results.md`

## Context

Regelsuche currently has several notions of sameness:

- `Expr.equals` compares the binary syntax structure, including grouping and order;
- Java reference identity distinguishes concrete parsed occurrences;
- canonical strings/hashes collapse selected algebraic variants;
- `TreePosition` addresses one subtree in one syntax root;
- e-classes represent broader equivalence under a rewrite theory.

For ordinary addition,

```text
(a+b)+c

a+(b+c)

c+a+b
```

have one mathematical value but different syntax. Likewise, `a+a+b` contains two
uses of the value `a` and must remain distinct from `a+b`.

A code audit found that current parsed node identity already has occurrence meaning:
`AstVisitorContext` stores metadata in an `IdentityHashMap<Expr, ...>`. Therefore the
existing syntax nodes cannot simply be globally interned without changing behavior.

## Decision

Regelsuche adopts **layered identity**:

1. **Syntax/notation identity** preserves grouping, order, formatting and source
   position.
2. **Occurrence identity** identifies one concrete use inside a syntax root or
   derivation step.
3. **Mathematical value identity** identifies an immutable expression under the
   explicitly declared laws of the active domain.
4. **E-class identity** represents broader, dynamic equivalence under selected
   rules, assumptions and budgets.

The selected representation is:

> Keep the current syntax AST as the notation/occurrence representation. Add a
> separate immutable mathematical value DAG whose equal values are interned inside
> an explicitly bounded factory scope. Link syntax occurrences to values through a
> typed occurrence index. Keep e-classes above ordinary value identity.

This is not two independently owned AST trees. The semantic side shares equal value
nodes, while the occurrence side retains every written use.

## Contracts

### Existing `Expr`

During migration:

- `Expr.equals` remains structural;
- existing constructors and exact syntax rules remain available;
- parsed `Expr` objects are not globally interned;
- local rewrites continue to address occurrences through `TreePosition`.

### Mathematical values

A new immutable value abstraction, provisionally `ExprValue`, will:

- exclude source spans, grouping, display order and occurrence IDs from equality;
- apply only operator laws explicitly declared for the domain;
- preserve multiplicity unless idempotence is explicitly declared;
- expose a deterministic structural `ValueKey` for persistence and cross-scope
  comparison.

For an associative and commutative sum:

```java
sameValue(sum(a, b, c), sum(c, a, b));
sameValue(sum(sum(a, b), c), sum(a, sum(b, c)));
!sameValue(sum(a, a, b), sum(a, b));
```

Subtraction, division and other non-commutative operators retain operand roles.

### Scoped interning

A bounded `ExprValueFactory` may guarantee reference-identical equal values inside
one parse bundle, search session or compilation unit:

```java
factory.variable("a") == factory.variable("a")
```

Reference identity is only a scoped fast path. Stable structural identity remains
authoritative across serialization and factory boundaries. An unbounded global
intern pool is rejected.

### Occurrences

A concrete occurrence has independent identity and references one value,
conceptually:

```java
record ExprOccurrence(
    OccurrenceId id,
    TreePosition position,
    Expr syntax,
    ExprValue value
) {}
```

A value has no single mutable parent or path. Parent relationships, source metadata,
provenance and local replacement belong to occurrences or edges.

## Set and multiplicity

Java `Set` is not rejected in general.

```java
Set<ExprOccurrence>
```

is valid for unordered uses: two different occurrence IDs can reference the same
interned value.

A bare

```java
Set<ExprValue>
```

cannot represent general addition by itself because value equality would collapse
the two `a` values in `a+a+b`. The value-level operation must preserve multiplicity,
for example with a multiplicity map, multiset, coefficient map, or explicit use
edges plus occurrence-independent semantic equality.

The mathematical contract, not one Java collection type, is normative.

## Evidence

### Existing architecture

The selected design aligns with existing components:

- `TreePosition` already identifies syntax occurrences;
- `TermOccurrenceIndex` already stores every path/role while grouping by a
  provisional canonical string;
- `ExpressionCanonicalizer.stableHash` is the current cross-subsystem value key;
- `EGraph` already hash-conses e-nodes and separates broader equivalence;
- PR #241 already separates exact recognition from bounded AC/broader recognition.

A full second semantic tree would duplicate structure and add a larger projection
and synchronization surface. Evolving the existing occurrence index toward typed
value references is the smaller model.

### Bounded spike

The temporary spike established:

- AC-equivalent syntax maps to one value identity;
- multiplicity remains significant;
- a normal set retains two occurrence IDs pointing to one value;
- one shared value can have several concrete paths;
- local replacement remains occurrence-based;
- stable keys preserve equality across factory scopes.

For three AC forms of `a+b+c`, the syntax had 15 occurrences. A duplicate semantic
projection allocated 15 semantic nodes; scoped interning retained 7 distinct values.
For `(a+b)*(a+b)`, 7 occurrences referenced 4 distinct values.

### Performance

JMH run 479 measured:

| Operation | Mean |
|---|---:|
| Non-interned projection | 77.224 µs/op |
| Interned projection, fresh scope | 115.989 µs/op |
| Interned projection, warm scope | 110.636 µs/op |
| Repeated-subexpression tree evaluation | 1.23194 µs/op |
| Memoized DAG evaluation | 0.178929 µs/op |

The prototype's interning construction was about 1.43–1.50× slower than plain
allocation, while repeated pure-value evaluation was about 6.88× faster. Therefore
value graphs must be built once per bounded owner and reused; they must not be
reconstructed inside every rule match.

## Boundaries

### PR #241

Recognition profiles remain responsible for exact-versus-broadened policy,
syntax-sensitive patterns, algebraic binding inference and allow-listed broader
equivalence. Shared value normalization may later replace duplicated AC
normalization but does not replace recognition policy.

### E-graph

`ExprValue` is one immutable canonical value. An e-class contains one or more
values/representations proven equivalent under a dynamic theory and assumptions.
The intended direction is:

```text
syntax occurrence -> ExprValue -> EClassId
```

### Compiled execution

Pure evaluator, matcher or generated-code caches may use `ExprValue` as a key.
Mutable tracing, explanation and runtime state must remain occurrence- or
execution-specific. This ADR permits compilation experiments but selects no
bytecode library.

## Rejected primary alternatives

- **Syntax AST alone:** leaves mathematical identity fragmented among strings,
  matchers and caches.
- **Semantic-only AST:** loses original notation and concrete didactic occurrences.
- **Two independent AST trees:** mathematically viable but duplicates equal semantic
  nodes and requires a larger synchronization graph.
- **Canonical keys only:** lack typed substructure and use relationships.
- **E-graph as ordinary value identity:** e-class identity is dynamic and
  theory-dependent.
- **Global interning:** creates unbounded retention, concurrency and test-isolation
  risks.

## Consequences

Benefits:

- mathematically irrelevant AC order/grouping leaves ordinary value identity;
- equal subexpressions can share analyses, caches and compiled artifacts;
- concrete occurrences remain independently selectable and explainable;
- canonical hashes can migrate incrementally to typed stable keys.

Costs:

- a value hierarchy, factory and operator-law policy must be maintained;
- construction overhead must be amortized and measured;
- persistence must re-intern stable structures on load;
- debugging tools may need to show both value identity and occurrence paths.

## Implementation

Production migration is tracked in issue #243 and proceeds incrementally:

1. immutable `ExprValue`, `ValueKey`, operator laws and bounded factory;
2. typed occurrence IDs and value links evolved from `TermOccurrenceIndex`;
3. canonicalization/search adapters and session-level caching;
4. PR #241 and e-graph integration;
5. optional compiled evaluator/matcher experiments.

Required safeguards include AC/multiplicity tests, ordered non-commutative roles,
occurrence-local replacement, stable cross-scope keys, bounded factory lifecycle,
no mutable occurrence metadata on shared values, and continued syntax-rule
compatibility.

## Non-goals

This ADR does not declare every operator associative/commutative, mandate one Java
collection implementation, select a bytecode library, immediately replace
`ExpressionCanonicalizer`, migrate all rules, or replace equality saturation.
