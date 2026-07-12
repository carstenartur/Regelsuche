# ADR: Choose the long-term expression identity and occurrence model

- Status: Proposed
- Date: 2026-07-12
- Related: PR #241, `ExpressionCanonicalizer`, `BinaryExpr`, equality saturation

## Context

Regelsuche currently represents mathematical expressions primarily with the binary
`Expr` hierarchy. For addition this preserves operand order and binary grouping:

```text
(a + b) + c

a + (b + c)
```

These are different trees even though ordinary addition is associative. Likewise,
`a + b + c` and `c + a + b` differ structurally although addition is commutative.

The current `ExpressionCanonicalizer` already flattens additions and
multiplications internally, groups equal terms or factors, orders them for stable
output and reconstructs a binary tree. This is useful for hashing and state
reduction, but it forces semantic information back into a syntax-shaped model and
does not retain an explicit relation to written occurrences.

The discussion leading to this ADR exposed a deeper decision than merely choosing
between one AST and two ASTs. Regelsuche must distinguish at least three concepts:

1. **expression value** — what mathematical expression an object denotes;
2. **occurrence or use** — where and in which parent context that value occurs;
3. **notation** — how the occurrence was written, grouped and positioned in source.

For example, in

```text
(a + b) * (a + b)
```

the subexpression `a + b` may be one shared expression value with two uses. In

```text
a + a + b
```

the value `a` may likewise be one interned object with two distinct uses.

This means that a normal Java `Set` is not automatically wrong for representing
unordered operands. It loses multiplicity only when its elements are expression
values whose `equals` relation identifies both uses. A set of distinct use or edge
objects can preserve both occurrences while remaining unordered. Conversely, a
multiset directly models multiplicity of values but does not by itself identify
which written occurrence contributed which copy.

The architecture therefore must decide not only whether addition is represented by
an unordered collection, but also where multiplicity, identity and provenance live.

## Decision question

Which expression architecture should Regelsuche adopt long term?

1. keep the existing binary `Expr` tree and external canonicalization;
2. replace it with one semantic-only AST;
3. maintain a syntax AST and a separate semantic AST connected by provenance;
4. keep the syntax AST and use only canonical keys or e-classes;
5. introduce an **interned semantic expression DAG** with separate occurrence/use
   and notation structures;
6. make the e-graph itself the primary semantic identity layer.

The decision must also determine whether equal mathematical expressions should be
represented by the same Java object through a factory or hash-consing mechanism.

## Decision drivers

### 1. Mathematical faithfulness

- Associative and commutative operators must not encode grouping or order as
  semantic information.
- Repeated operands must retain their multiplicity.
- Deterministic display order must not become semantic order.

### 2. Stable and explicit identity

- The design must distinguish value identity from occurrence identity.
- Copying, parsing and deserialization must not silently change mathematical
  identity semantics.
- If object identity is used as a fast equality path, it must be guaranteed by a
  central factory rather than assumed informally.

### 3. Didactic traceability

- Regelsuche must explain which written occurrences participated in a rewrite.
- Original grouping, order and source spans must remain available where relevant.
- The architecture must support replacing one occurrence without unintentionally
  replacing every use of the same shared value.

### 4. Search and rule performance

- Equivalent permutations and regroupings should not inflate the semantic search
  space.
- Matching and rule analysis should be cacheable per expression value where safe.
- Common subexpressions should not be re-analysed unnecessarily.

### 5. Rule-system compatibility

- Existing exact AST rules must remain usable during migration.
- PR #241's bounded associative/commutative recognition must not be invalidated or
  duplicated without a clear replacement.
- Rule authors should not manually maintain identity or provenance indices.

### 6. Compilation potential

The architecture should not require bytecode compilation now, but it should permit:

- caching compiled evaluators per expression value;
- common-subexpression elimination;
- shared compiled fragments for repeated subexpressions;
- compiled rule predicates or matchers;
- method-handle, hidden-class or generated-bytecode implementations without making
  compilation semantics depend on source occurrence identity.

### 7. Cognitive and implementation cost

- Most rule authors should interact with one clear abstraction at a time.
- Parent/use indices and provenance must be maintained centrally.
- Debugging and serialization must remain comprehensible.

### 8. Incremental migration

The architecture must be testable without replacing the public `Expr` hierarchy in
one repository-wide change.

## Required conceptual distinction

Any accepted design must represent these concepts separately, even if some are
implemented as views rather than permanent graphs.

### Expression value

Answers:

> What expression is this?

Its equality excludes source position and occurrence identity.

### Occurrence or use

Answers:

> Where is this expression used in this concrete expression graph?

A use may contain:

- parent identity;
- operand role or edge label;
- occurrence ID;
- source span;
- projection/provenance metadata.

### Notation

Answers:

> How was this occurrence written?

It preserves grouping, operand order and formatting-relevant information.

An `Expr` value must not expose a single `parent()` or single tree position if the
same value can have several uses.

## Alternatives considered

### A. Keep only the binary `Expr` tree

Canonicalization, matching and e-graph logic continue compensating for syntactic
variants.

**Advantages**

- lowest immediate implementation cost;
- no new identity layer;
- current rules and local tree positions remain unchanged.

**Disadvantages**

- semantic equality remains external to the model;
- subsystems may repeat flattening and AC handling;
- equivalent groupings and permutations complicate search;
- common subexpressions are represented repeatedly;
- object identity has no useful semantic guarantee.

### B. Replace `Expr` with a semantic-only AST

Addition and multiplication become operation-specific n-ary nodes and syntax is
reconstructed for display.

**Advantages**

- mathematically direct model;
- small semantic search space;
- one primary representation.

**Disadvantages**

- original notation cannot be reconstructed reliably;
- high migration risk for local rewrites and didactic explanations;
- concrete occurrence identity still has to be added somewhere;
- rejected as an immediate replacement.

### C. Syntax AST plus semantic AST and projection graph

The syntax AST preserves occurrences, grouping, order and positions. A semantic AST
uses operation-specific mathematical structures. A separate graph maps between
both.

**Advantages**

- explicit separation of notation and meaning;
- bidirectional highlighting and explanations;
- semantic deduplication while retaining syntax evidence;
- natural place for rewrite provenance.

**Disadvantages**

- two full node hierarchies may duplicate structure;
- synchronization, serialization and debugging are complex;
- equal subexpressions may still be duplicated inside the semantic AST;
- rule authors face a larger conceptual surface if APIs are not carefully layered.

### D. Syntax AST plus canonical keys or e-classes only

No permanent semantic AST is introduced. Canonicalization or equality saturation
provides equivalence information.

**Advantages**

- lower implementation cost than two full models;
- fits transposition tables and existing e-graph work;
- avoids synchronizing two trees.

**Disadvantages**

- canonical keys do not expose internal semantic substructure;
- e-classes alone do not identify written uses;
- provenance from syntax occurrences to semantic operands remains underspecified;
- compilation caches need another stable unit of identity.

### E. Interned semantic expression DAG plus separate uses and notation

A central `ExprFactory` interns expression values. Structurally and semantically
identical values return the same object where the selected operator laws permit it.
Concrete occurrences are represented separately as edges or use objects.

Example:

```text
Notation / occurrence graph             Interned value DAG

(a + b) * (a + b)                            Product
       \       /                              /    \
        two uses --------------------------> Sum  Sum
                                                \  /
                                           same Sum object
```

A possible boundary is:

```java
interface Expr {}

record ExprUse(
    UseId id,
    Expr parent,
    Expr child,
    OperandRole role,
    SourceSpan sourceSpan
) {}
```

The concrete API need not use these exact records.

For an associative and commutative sum, the factory may build an operation-specific
key whose equality ignores operand order and grouping while retaining multiplicity.
The underlying node may use:

- a multiset of expression values;
- a coefficient map;
- an unordered set of use/edge objects;
- another immutable representation with the same public semantics.

The ADR deliberately does not equate mathematical semantics with one Java
collection type.

**Advantages**

- `==` can become a valid fast path for equal interned values;
- common subexpressions are shared;
- rule results, analyses and compiled evaluators can be cached per value;
- reverse-use indices support pattern search from a value toward parent contexts;
- expression identity and occurrence identity become explicit;
- naturally supports DAG-based evaluation and common-subexpression elimination.

**Disadvantages**

- all construction must go through a trusted factory;
- direct `new BinaryExpr(...)` construction must eventually be restricted;
- weak-reference, lifecycle or pool-size policy is required;
- global interning can create memory retention and concurrency concerns;
- replacing one use versus all uses must be explicit;
- semantic normalization policy becomes part of factory correctness.

### F. E-graph-centred semantic identity

Expression values are represented primarily as e-nodes and e-classes, with syntax
occurrences projected into the e-graph.

**Advantages**

- equivalence is first-class;
- naturally supports equality saturation;
- can share many equivalent representations.

**Disadvantages**

- e-class identity is not identical to one expression value;
- extraction policy affects which representation is compiled or displayed;
- occurrence provenance still requires a separate layer;
- may be too heavyweight for every parsed expression and ordinary rule operation.

## Factory and interning invariants

If alternative E is selected, the following must hold:

```java
Expr a1 = factory.variable("a");
Expr a2 = factory.variable("a");
assert a1 == a2;
```

For operators declared associative and commutative in the active domain:

```java
assert factory.sum(a, b, c) == factory.sum(c, a, b);
assert factory.sum(factory.sum(a, b), c) == factory.sum(a, factory.sum(b, c));
```

Multiplicity must remain observable:

```java
assert factory.sum(a, a, b) != factory.sum(a, b);
```

These guarantees must survive parser entry points and controlled deserialization.
They must not rely on incidental JVM allocation behaviour.

## Parent and use navigation

An interned value can have several parents and several occurrences. Parent-oriented
search therefore requires a separate index, for example conceptually:

```java
Map<Expr, Set<ExprUse>> usesByChild;
```

This can support:

- finding all contexts in which a value participates;
- matching patterns upward from a selected expression;
- applying one discovered rule result at several uses;
- incremental invalidation;
- impact analysis;
- identifying shared subexpressions before compilation.

The index must not be stored as mutable parent collections inside an immutable
`Expr` value unless ownership, lifecycle and concurrency are rigorously defined.

## Bytecode and compiled execution implications

No decision in this ADR requires runtime bytecode generation. The spike must,
however, evaluate whether the chosen value identity is a suitable compilation key.

An interned DAG could permit:

```text
(a + b)^2 + sin(a + b)
```

to compile conceptually as:

```java
double t0 = a + b;
return t0 * t0 + Math.sin(t0);
```

Potential caches include:

```java
Map<Expr, CompiledExpression> compiledExpressions;
Map<RuleAndExpr, MatchProgram> compiledMatchers;
```

The evaluation must distinguish:

- compile once per expression value;
- execute once per value where common-subexpression semantics permit it;
- preserve occurrence-specific behaviour for explanations, tracing or
  non-pure operations.

Only pure mathematical expression values are candidates for this optimisation.

## Proposed decision process

This ADR does not authorize a repository-wide AST migration. The decision is made
through the following gates.

### Gate 1: Executable invariants

Create tests covering at least:

- AC-equivalent additions share semantic identity;
- `a + a + b` retains multiplicity two;
- subtraction is not made commutative;
- source grouping and spans remain available;
- one value may have several uses;
- replacing one use does not replace all uses accidentally;
- semantic equality ignores use IDs and display order;
- factory interning, if enabled, makes equal values reference-identical;
- formatting remains deterministic without defining equality.

### Gate 2: Bounded competing spikes

Implement two internal spikes for parsed addition without replacing existing rule
APIs.

#### Spike C: dual AST plus projection

```java
record ParsedExpression(
    Expr syntax,
    SemanticExpr semantic,
    ProjectionGraph projection
) {}
```

#### Spike E: interned value DAG plus uses

```java
record ParsedExpressionGraph(
    SyntaxView syntax,
    Expr semanticRoot,
    UseGraph uses
) {}
```

Both spikes must be produced centrally. Ordinary rules must not mutate projection
or use links manually.

### Gate 3: Search and recognition measurements

Run the same bounded corpus with the baseline and both spikes. Record:

- distinct semantic states;
- parse and construction time;
- matching and search runtime;
- memory use and retained-object counts;
- number of shared subexpressions;
- rule-analysis cache hit rates;
- number of rules requiring adaptation;
- overlap with PR #241's AC-aware recognition;
- explanation quality for `a + a + b -> 2*a + b`;
- ability to select and rewrite exactly one occurrence.

### Gate 4: Compilation-oriented experiment

Without committing to a production compiler, build a minimal evaluator or
method-handle prototype for a small pure expression corpus. Compare:

- tree evaluation versus DAG evaluation;
- compile/cache reuse for repeated subexpressions;
- construction overhead of interning;
- whether stable value identity materially simplifies the compiler cache.

This gate may use an interpreter with explicit common-subexpression caching if
bytecode generation would distract from the architecture question.

### Gate 5: Cognitive-cost review

Review representative code paths:

- parser;
- one exact syntactic rule;
- one AC-aware rule;
- local rewrite selection;
- explanation/highlighting;
- serialization;
- one search cache;
- optional compiled evaluator.

Count new concepts and mandatory API decisions for ordinary rule authors. An
architecture that performs well but requires every rule to reason about values,
uses, syntax nodes, e-classes and projection links is not acceptable.

### Gate 6: Final selection

Select alternative E if it demonstrates:

- reliable factory-enforced value identity;
- meaningful common-subexpression sharing or cache reuse;
- precise occurrence-level rewriting and provenance;
- bounded memory and manageable pool lifecycle;
- simpler or faster rule/search/compiler paths than the alternatives.

Select alternative C if explicit semantic structure and projection prove valuable
but global interning does not justify its lifecycle and implementation cost.

Select alternative D if semantic deduplication helps but neither durable semantic
nodes nor use/provenance graphs justify their cost.

Keep alternative A if measured benefits are minor.

Alternative B remains rejected for the current project because original notation
and didactic steps are core requirements.

Alternative F should be selected only if the e-graph can serve normal expression
identity and occurrence mapping without forcing equality-saturation complexity into
ordinary operations.

### Gate 7: Record the decision

Update this ADR with:

- `Status: Accepted` or `Rejected`;
- the selected alternative;
- measurements and rejected alternatives;
- identity and lifecycle invariants;
- migration stages;
- follow-up implementation issues.

No repository-wide migration may begin while this ADR remains `Proposed`.

## Preliminary recommendation

The leading experiment is now **alternative E: an interned semantic expression DAG
plus a separate use/notation graph**. Alternative C remains the primary fallback.

This is not yet the decision. Alternative E earns preference for the spike because
it may unify several concerns that otherwise require separate mechanisms:

- mathematical value identity;
- common-subexpression sharing;
- AC state reduction;
- reverse context search;
- rule and analysis caching;
- occurrence-level provenance;
- future compiled execution.

The spike must disprove the risk that this apparent unification merely moves
complexity into factory invariants, pool lifecycle and use-graph maintenance.

## Provisional model boundaries

### Syntax or notation layer

Owns:

- entered grouping and order;
- source spans;
- concrete occurrence identity;
- exact rewrite presentation.

### Interned expression value layer

Owns:

- immutable mathematical operators and operands;
- operator-specific equality laws;
- stable hashing;
- optional object-identity guarantee through the factory;
- no source spans and no single-parent relation.

### Use and provenance layer

Owns:

- parent-child uses and operand roles;
- multiple uses of one value;
- reverse lookup from value to contexts;
- syntax-to-value mapping;
- transformation provenance;
- occurrence-specific selection and replacement.

### Formatter

Chooses a deterministic representation. Its ordering is not semantic identity.

### E-graph

Owns equivalence among expression values or representations. It does not by itself
replace concrete use identity or syntax provenance.

### Compiler/cache layer

Uses immutable expression values as candidate keys. It must not attach mutable
runtime state directly to globally interned values.

## Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Intern pool retains all expressions forever | Compare scoped factories, weak interning and repository/search-session lifetimes |
| Direct constructors violate identity guarantees | Keep spike internal; route construction through one factory before considering API restrictions |
| Shared values make local replacement ambiguous | Require explicit `UseId` or path when replacing one occurrence |
| Parent links make immutable values mutable | Store reverse links in a separate owned index |
| Value equality and occurrence equality are confused | Separate types and tests; exclude occurrence IDs from value equality |
| AC factory normalization becomes too aggressive | Declare operator laws by domain and preserve non-AC operators unchanged |
| PR #241 duplicates factory-level AC identity | Measure whether recognition profiles remain necessary for syntax patterns and non-canonical inputs |
| Bytecode goals distort the core model | Use compilation only as one measured decision driver, not as a mandatory feature |
| Two or three layers overwhelm rule authors | Provide syntax-rule and value-rule facades; ordinary rules must not manage use graphs |
| Serialization destroys interning | Re-intern through the factory during load and serialize value structure separately from uses |

## Non-goals

This ADR does not decide:

- that every operator is associative or commutative;
- that subtraction is unordered;
- a specific Java collection implementation for sums;
- a specific bytecode library;
- that runtime compilation must be implemented;
- global versus scoped interning before measurement;
- replacement of equality saturation;
- a repository-wide rewrite of rules;
- the final persistence format.

These require evidence from the spikes or follow-up ADRs.