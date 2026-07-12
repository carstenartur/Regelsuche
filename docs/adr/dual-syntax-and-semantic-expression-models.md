# ADR: Define mathematical expression identity

- Status: Proposed
- Date: 2026-07-12
- Related: PR #241, `ExpressionCanonicalizer`, `BinaryExpr`, equality saturation

## Decision to make

The primary architecture decision is:

> **When do two representations denote the same expression in Regelsuche, and how
> is that identity represented in memory?**

The following questions are consequences of that decision rather than independent
starting points:

- whether the core representation is a tree or DAG;
- whether equal values are interned by a factory;
- whether syntax and mathematical value use separate node models;
- whether parent positions belong to nodes or to edges/occurrences;
- whether canonicalization or an e-graph defines identity;
- whether rule, search and compiled-execution caches can use `Expr` directly as a
  stable key.

This ADR must first define the identity domains and their laws. Only then may it
choose the concrete representation.

## Context

Regelsuche currently represents expressions primarily through the binary `Expr`
hierarchy. For ordinary addition, expressions such as

```text
(a + b) + c

a + (b + c)

c + a + b
```

are structurally different even though associativity and commutativity make them
the same mathematical sum in the relevant domain.

The current `ExpressionCanonicalizer` already flattens and groups terms, imposes a
stable output order and reconstructs a binary expression. Thus Regelsuche already
uses more than one practical notion of sameness:

- structural equality of the parsed binary tree;
- equality of canonical output;
- equality under selected rewrite rules or e-classes;
- identity of a concrete occurrence used by local rewrites and explanations.

These notions are useful, but their boundaries are not yet explicit.

The discussion leading to this ADR also showed that a Java collection type cannot
be chosen correctly before identity is defined. A `Set<Expr>` loses two occurrences
of `a` only if both elements are equal according to the set's equality relation. If
`Expr` denotes a shared mathematical value and occurrences are separate edge or use
objects, a normal set of uses may preserve multiplicity while remaining unordered.
A multiset is another possible implementation, but it is not itself the
architecture decision.

## Identity domains

Regelsuche needs at least four explicitly separated identity domains.

### 1. Mathematical value identity

Answers:

> Do these objects denote the same mathematical expression under the laws selected
> for this domain?

For ordinary addition this may identify regroupings and permutations while still
distinguishing

```text
a + a + b
```

from

```text
a + b
```

Mathematical value identity excludes source spans, formatting and occurrence IDs.
It must not depend on collection iteration order.

### 2. Representation identity

Answers:

> Are these the same chosen representation of a mathematical value?

An e-class may contain several representations of one value. Conversely, a
canonical semantic node may choose one representation as the stable value object.
The architecture must not silently equate representation identity with full
mathematical equivalence unless the supported theory makes that safe and bounded.

### 3. Occurrence or use identity

Answers:

> Is this the same concrete use of an expression inside a containing expression or
> derivation step?

In

```text
(a + b) * (a + b)
```

there may be one mathematical `a + b` value but two uses. In

```text
a + a + b
```

there may be one mathematical variable value `a` with two uses.

Occurrence identity is required for:

- replacing one occurrence rather than all uses;
- source highlighting;
- local tree positions;
- provenance and explanations;
- preserving multiplicity when several uses point to one value.

### 4. Notation identity

Answers:

> Is this the same written or generated notation?

It includes grouping, operand order, explicit parentheses, source position and
possibly display-specific choices. Notation identity is not mathematical identity.

## Required invariants

Any accepted architecture must satisfy these invariants.

### Mathematical laws are explicit

Associativity, commutativity, idempotence and other properties are declared per
operator and domain. They are not inferred from Java collection types.

For an operator declared associative and commutative:

```java
sameValue(sum(a, b, c), sum(c, a, b));
sameValue(sum(sum(a, b), c), sum(a, sum(b, c)));
```

Multiplicity remains significant unless a separate law says otherwise:

```java
!sameValue(sum(a, a, b), sum(a, b));
```

Subtraction is not accidentally made commutative.

### Values do not have one parent

A mathematical expression value may be used several times. Therefore a value node
must not expose one authoritative mutable `parent()` or one tree position.
Parent relationships belong to occurrences, uses or graph edges.

### Local replacement is occurrence-based

Replacing one selected occurrence must not replace every use of a shared value.
Replacing the mathematical value globally must be a different explicit operation.

### Formatting does not define identity

A deterministic formatter may sort operands for stable output, but display order
must not participate in mathematical equality.

### Construction preserves the chosen identity contract

Parsing, factories, copying and deserialization must produce values that obey the
same identity rules. Object identity may be used as an optimisation only when a
factory guarantees it.

## Decision drivers

1. **Mathematical faithfulness** — the model must encode operator laws without
   adding irrelevant order or grouping information.
2. **Didactic traceability** — concrete written occurrences and derivation history
   must remain addressable.
3. **Search-space control** — equivalent syntactic variants should not create
   redundant semantic states unless intentionally explored.
4. **Rule compatibility** — existing exact rules and PR #241's bounded AC-aware
   recognition need an incremental migration path.
5. **Performance** — identity should enable safe caching, common-subexpression
   sharing and potentially compiled execution.
6. **Cognitive cost** — ordinary rule authors must not manually coordinate values,
   uses, syntax nodes, projections and e-classes.
7. **Lifecycle safety** — interning, reverse-use indices and caches must have bounded
   ownership and memory behaviour.
8. **Persistence stability** — deserialization must reconstruct the same value and
   occurrence relations without relying on transient JVM identities.

## Candidate identity policies

Before comparing data structures, the spike must compare these identity policies.

### Policy P1: structural syntax identity

Two expressions are identical only if operator, child order and binary grouping are
identical.

This matches the current `BinaryExpr` shape but requires canonicalization or
matching logic elsewhere for mathematical equivalence.

### Policy P2: canonical semantic value identity

Two expressions have the same value identity when a bounded, explicitly defined
canonical constructor maps them to the same semantic structure.

For AC addition, operand order and grouping are removed from the value identity.
This can support stable hashing and interning.

### Policy P3: equivalence-class identity

Two representations have the same mathematical identity when they belong to the
same e-class under an explicitly selected rewrite theory and budget.

This makes equivalence first-class but may be too broad or dynamic to serve as the
ordinary identity of every `Expr` object.

### Policy P4: layered identity

Use canonical semantic value identity for ordinary immutable values and e-classes
for broader equivalence among those values. Keep occurrence and notation identity
separate.

This is the preliminary leading policy, but it must be tested rather than assumed.

## Representation alternatives derived from the identity policy

### A. Binary syntax tree plus external canonicalization

`Expr` remains a syntax-shaped value and mathematical identity remains external.

**Advantages**

- lowest migration cost;
- existing rules and tree positions remain valid;
- no new construction discipline.

**Disadvantages**

- identity remains fragmented across subsystems;
- equivalent groupings and permutations complicate search;
- common subexpressions are duplicated;
- `Expr` is a weak cache key for semantic work.

### B. Semantic-only AST

Replace the syntax tree with operation-specific semantic nodes.

**Advantages**

- direct mathematical model;
- small semantic state space.

**Disadvantages**

- notation and concrete occurrences still need a second mechanism;
- original input cannot be reconstructed reliably;
- unsuitable as an immediate replacement for didactic and local-rewrite features.

### C. Syntax AST plus separate semantic AST and projection graph

Maintain two node hierarchies and explicit many-to-many mappings.

**Advantages**

- clean separation of notation and meaning;
- explicit bidirectional provenance;
- semantic structure remains navigable.

**Disadvantages**

- duplicated structure and synchronisation cost;
- equal semantic subexpressions may still be duplicated;
- large conceptual surface.

### D. Syntax AST plus canonical keys or e-classes

Keep the syntax tree and add non-node semantic identities.

**Advantages**

- lower implementation cost;
- fits transposition tables and existing e-graph work.

**Disadvantages**

- canonical keys do not expose internal semantic structure;
- concrete uses and provenance remain underspecified;
- compiled-expression caches need another stable unit.

### E. Interned semantic value DAG plus occurrence and notation graphs

A central factory creates immutable mathematical values. Equal canonical values may
be represented by the same Java object. Concrete uses and notation are separate.

Conceptually:

```java
interface ExprValue {}

record ExprUse(
    UseId id,
    ExprValue parent,
    ExprValue child,
    OperandRole role,
    SourceSpan sourceSpan
) {}
```

The concrete API may differ. The important boundary is that values and uses are not
the same objects.

For selected AC operators, the factory key ignores order and grouping while
preserving multiplicity. The internal collection may be a multiset, coefficient
map, set of use edges or another immutable structure whose public semantics satisfy
the invariants.

**Advantages**

- shared common subexpressions;
- `==` can be a valid fast path inside one factory scope;
- rule analyses and compiled evaluators can be cached per value;
- reverse-use indices permit upward context search;
- occurrence-specific rewrites remain possible;
- natural basis for DAG evaluation and common-subexpression elimination.

**Disadvantages**

- all value construction must eventually use a trusted factory;
- pool scope, weak references, concurrency and persistence require design;
- local versus global replacement must be explicit;
- canonical constructor correctness becomes foundational.

### F. E-graph as the primary value model

Use e-nodes and e-classes as the ordinary semantic layer.

**Advantages**

- equivalence is first-class;
- works naturally with equality saturation.

**Disadvantages**

- one e-class is not one chosen representation;
- extraction policy affects display, compilation and matching;
- occurrence identity remains separate;
- may impose equality-saturation complexity on ordinary operations.

## Factory and interning implications

If policy P2 or P4 with representation E is selected, the factory should make these
properties testable within a declared scope:

```java
ExprValue a1 = factory.variable("a");
ExprValue a2 = factory.variable("a");
assert a1 == a2;

assert factory.sum(a, b, c) == factory.sum(c, a, b);
assert factory.sum(factory.sum(a, b), c)
    == factory.sum(a, factory.sum(b, c));

assert factory.sum(a, a, b) != factory.sum(a, b);
```

Reference identity is then an implementation guarantee inside the factory scope,
not the persisted definition of mathematical equality. Stable structural keys must
remain available for persistence and cross-scope comparison.

## Parent and pattern navigation

A shared value can have several uses. Upward navigation therefore belongs in a
separate owned index, conceptually:

```java
Map<ExprValue, Set<ExprUse>> usesByChild;
```

This can support:

- finding all parent contexts of a value;
- beginning pattern discovery at a selected value and searching outward;
- reusing one rule analysis at several occurrences;
- incremental invalidation and impact analysis;
- finding common subexpressions before evaluation or compilation.

The index must not make otherwise immutable value nodes globally mutable.

## Compiled execution implications

No runtime compiler is required by this ADR. However, value identity may become the
key for compiled or interpreted execution caches.

For

```text
(a + b)^2 + sin(a + b)
```

an interned DAG can expose the shared `a + b` value and permit conceptual code such
as:

```java
double t0 = a + b;
return t0 * t0 + Math.sin(t0);
```

Potential caches include:

```java
Map<ExprValue, CompiledExpression> compiledExpressions;
Map<RuleAndValue, MatchProgram> compiledMatchers;
```

This benefit must be measured. It must not determine the architecture by itself.
Only pure mathematical values may be shared or evaluated this way; tracing and
explanation remain occurrence-specific.

## Decision process

This ADR does not authorize a repository-wide migration. The decision is produced
through the following gates.

### Gate 1: Specify executable identity laws

Tests must cover:

- structural identity versus mathematical value identity;
- AC-equivalent additions;
- significant multiplicity;
- non-commutative subtraction;
- one value with several occurrences;
- local replacement of one occurrence;
- deterministic formatting independent of equality;
- persistence or reconstruction of stable value keys;
- interning guarantees where enabled.

### Gate 2: Inventory current identity assumptions

Identify where Regelsuche currently relies on:

- `Expr.equals`;
- reference identity;
- formatted canonical strings;
- tree paths;
- transposition-table hashes;
- e-class membership;
- parent or subtree reconstruction.

Classify each use as value, representation, occurrence or notation identity. This
inventory is required before migration estimates are trusted.

### Gate 3: Build bounded competing spikes

Implement parsed addition in internal experimental packages without changing public
rule APIs.

#### Spike C: dual AST and projection

```java
record ParsedExpression(
    Expr syntax,
    SemanticExpr semantic,
    ProjectionGraph projection
) {}
```

#### Spike E: interned values and uses

```java
record ParsedExpressionGraph(
    SyntaxView notation,
    ExprValue valueRoot,
    UseGraph uses
) {}
```

Both must preserve source occurrences and AC value identity. Ordinary rules must
not maintain mappings manually.

### Gate 4: Measure search and rule behaviour

Using the same corpus, record:

- distinct syntax and value states;
- construction and parsing time;
- matching and search runtime;
- memory and retained-object counts;
- common-subexpression sharing;
- analysis-cache hit rates;
- rule migration count;
- overlap with PR #241;
- explanation quality for `a + a + b -> 2*a + b`;
- correctness of single-occurrence rewrites.

### Gate 5: Test compilation-key value

Build a small pure evaluator or method-handle/interpreter prototype. Compare tree
and DAG execution, reuse of repeated subexpressions and cache-key simplicity. Full
bytecode generation is unnecessary if it would obscure the identity question.

### Gate 6: Review cognitive cost

Review representative parser, rule, local rewrite, explanation, persistence,
search-cache and optional evaluator code. Ordinary rule authors must not need to
reason about all identity layers simultaneously.

### Gate 7: Select identity policy first

Choose among P1–P4 based on the evidence. Record explicitly:

- what `Expr.equals` means;
- whether `Expr` denotes a value or occurrence;
- whether equal values are interned;
- factory scope and lifecycle;
- the role of canonical keys;
- the relationship between values and e-classes.

### Gate 8: Select representation second

Only after Gate 7, select A–F as the representation that best implements the chosen
identity policy.

### Gate 9: Finalise the ADR

Update this file with:

- `Status: Accepted` or `Rejected`;
- selected identity policy;
- selected representation;
- measurements;
- rejected alternatives;
- migration stages and follow-up issues.

No repository-wide migration may start while this ADR remains `Proposed`.

## Preliminary hypothesis

The leading hypothesis is:

- **P4 layered identity**: canonical semantic value identity for ordinary immutable
  values, separate occurrence and notation identity, and e-classes for broader
  equivalence;
- implemented initially by **E**, an interned semantic value DAG with separate use
  and notation structures;
- with **C** as fallback if explicit semantic projection is valuable but interning
  lifecycle and factory complexity are not justified.

This is deliberately a hypothesis, not the decision. The spike must test whether
one interned value layer genuinely unifies:

- mathematical identity;
- AC deduplication;
- common-subexpression sharing;
- rule and search caching;
- parent-context navigation;
- occurrence provenance;
- future compiled execution.

It must also test whether that apparent unification merely moves excessive
complexity into canonical construction, pool ownership and use-graph maintenance.

## Provisional boundaries under the leading hypothesis

### Mathematical value layer

Owns immutable operators, operands, declared operator laws, stable structural keys
and optional factory-scoped reference identity. It has no source span and no single
parent.

### Occurrence/use layer

Owns parent-child uses, operand roles, multiplicity of uses, source selection,
reverse context lookup and local replacement identity.

### Notation layer

Owns grouping, written order, explicit parentheses, source spans and exact rewrite
presentation.

### E-graph layer

Owns broader equivalence among mathematical values or representations. It does not
replace occurrence identity.

### Cache/compiler layer

May key pure analysis or compiled execution by immutable mathematical value. Mutable
runtime state must not be attached directly to globally shared values.

## Risks and mitigations

| Risk | Mitigation |
|------|------------|
| The word "identity" hides several meanings | Require every API and test to classify value, representation, occurrence or notation identity |
| Intern pools retain expressions indefinitely | Compare scoped factories, weak interning and search-session ownership |
| Direct constructors violate factory guarantees | Keep the spike internal and restrict constructors only after acceptance |
| Sharing values makes local replacement ambiguous | Require an explicit occurrence/use identifier for local rewrites |
| Mutable parent links corrupt shared values | Keep parent/use relations in a separately owned graph or index |
| AC canonical construction becomes too aggressive | Declare laws per operator/domain and preserve unsupported distinctions |
| Persistence loses JVM reference identity | Persist stable structural keys and re-intern on load |
| PR #241 duplicates canonical AC matching | Measure which syntax-recognition capabilities remain necessary |
| Bytecode ambitions distort the core decision | Treat compilation as one measured driver, not a mandatory outcome |
| Layering increases cognitive load | Provide focused syntax-rule and value-rule facades; mappings remain framework-owned |

## Non-goals

This ADR does not yet decide:

- that every operator is associative or commutative;
- that subtraction is unordered;
- a concrete Java collection type for sums;
- global versus scoped interning;
- a bytecode library or requirement to generate bytecode;
- replacement of equality saturation;
- a repository-wide rule rewrite;
- the final persistence format.

Those decisions follow from measured evidence or later ADRs.