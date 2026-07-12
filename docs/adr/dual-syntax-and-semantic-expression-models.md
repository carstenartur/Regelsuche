# ADR: Separate syntactic and semantic expression models

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

are different trees even though ordinary addition is associative, and permutations
such as `a + b + c` and `c + a + b` are equal because addition is commutative.

The current `ExpressionCanonicalizer` already flattens additions and
multiplications internally, groups equal terms or factors, orders them for stable
output and finally reconstructs a binary tree. This is useful for hashing and
state deduplication, but it forces semantic information back into a syntax-shaped
model and does not retain an explicit relation to the original occurrences.

Regelsuche needs both perspectives:

- the entered or generated notation, including grouping, order and source positions;
- the mathematical value structure, excluding information that is irrelevant under
  the applicable algebraic laws;
- traceability between both perspectives for explanations, highlighting, local
  rewrites, misconception detection and learned rule recognition.

For an associative and commutative operation, a sorted list is not the semantic
model: order is not part of the operation's information content. A normal Java
`Set` is also insufficient because it loses multiplicities such as the two
occurrences in `a + a + b`. The corresponding semantic structure is a multiset.

## Decision question

Should Regelsuche maintain:

1. only the existing binary expression tree;
2. one replacement semantic AST;
3. a syntactic AST and a semantic AST with explicit many-to-many provenance links;
4. no second AST, using only canonical keys or e-classes beside the syntax tree?

## Decision drivers

The decision must be based on observable effects rather than elegance alone.

1. **Mathematical faithfulness**
   - Associative and commutative operators must not encode grouping or order as
     semantic information.
   - Repeated operands must retain their multiplicity.

2. **Didactic traceability**
   - Regelsuche must still explain which written occurrences participated in a
     transformation.
   - Original grouping and order must remain available where they are pedagogically
     relevant.

3. **Search-space reduction**
   - Equivalent permutations and regroupings should not create redundant semantic
     states unless the search intentionally studies those syntactic moves.

4. **Rule-system compatibility**
   - Existing exact AST rules must remain usable during migration.
   - PR #241's bounded associative/commutative recognition must not be invalidated
     or duplicated without a clear replacement.

5. **Cognitive and implementation cost**
   - Most rule authors should not need to reason about two ASTs or maintain links
     manually.
   - Projection and provenance updates must be centralized and testable.

6. **Identity stability**
   - Semantic equality must not depend on occurrence IDs, iteration order, display
     order or source positions.
   - Occurrence IDs may exist only in the projection/provenance layer.

7. **Incremental migration**
   - The architecture must permit an experiment without replacing the public `Expr`
     hierarchy in one step.

## Alternatives considered

### A. Keep only the binary `Expr` tree

Canonicalization, matching and e-graph logic continue compensating for syntactic
variants.

**Advantages**

- lowest immediate implementation cost;
- no dual-model synchronization;
- current rules and local tree positions remain unchanged.

**Disadvantages**

- semantic equality remains external to the model;
- every subsystem may implement its own flattening and AC handling;
- regroupings and permutations continue to inflate or complicate search;
- provenance is accidental rather than explicit.

### B. Replace `Expr` with a semantic AST

Addition and multiplication become n-ary multiset nodes; syntax is reconstructed
only for display.

**Advantages**

- mathematically direct model;
- smallest semantic search space;
- no synchronization between two primary models.

**Disadvantages**

- loses original grouping and ordering unless separately captured;
- high migration risk for local rewrites, didactic explanations and exact rules;
- a pretty printer cannot reconstruct the user's original notation;
- unsuitable as an immediate replacement.

### C. Maintain a syntax AST and a semantic AST with a projection graph

The syntax AST preserves occurrences, grouping, order and positions. The semantic
AST uses operation-specific mathematical structures. A separate immutable
projection records many-to-many relations.

Example:

```text
Syntax AST                         Semantic AST

       +                              Sum
      / \                         {a:1, b:1, c:1}
     +   c                              ^
    / \                                 |
   a   b                         ProjectionGraph
```

**Advantages**

- preserves notation and mathematical meaning without conflating them;
- enables bidirectional highlighting and explanations;
- permits semantic deduplication while retaining syntactic evidence;
- provides a foundation for transformation provenance.

**Disadvantages**

- highest conceptual and implementation cost;
- many-to-many links cannot be maintained correctly by ad hoc rule code;
- duplicate semantic values still require occurrence references in the projection;
- caching, serialization and debugging become more complex.

### D. Keep the syntax AST and add only canonical keys or e-classes

No permanent semantic AST is introduced. Canonicalization or equality saturation
supplies semantic equivalence classes and stable keys.

**Advantages**

- lower cost than a complete second AST;
- integrates naturally with transposition tables and the existing e-graph module;
- avoids permanent synchronization of two trees.

**Disadvantages**

- a canonical key cannot represent internal semantic substructure or provenance;
- e-classes express equivalence but not necessarily the operation-specific
  information model needed by explanations and selection;
- mappings from written occurrences to semantic operands remain underspecified.

## Proposed decision process

This ADR does not authorize a repository-wide AST migration. The decision is made
through the following gated process.

### Gate 1: Define invariants and representative cases

Create executable tests for at least these cases:

- `(a + b) + c`, `a + (b + c)` and `c + a + b` share one semantic sum;
- `a + a + b` retains multiplicity two for `a`;
- subtraction is represented without incorrectly declaring it commutative;
- syntax source spans and grouping remain available;
- forward and reverse projection identify all contributing occurrences;
- semantic equality and hash codes ignore occurrence IDs and iteration order;
- formatting is deterministic but formatting order is not semantic identity.

### Gate 2: Implement a bounded vertical spike

Implement the dual representation only for parsed additions in an internal,
experimental package. The spike must contain:

```java
record ParsedExpression(
    Expr syntax,
    SemanticExpr semantic,
    ProjectionGraph projection
) {}
```

and a semantic sum whose public contract is multiset semantics. No existing rule
API is replaced in this gate.

The projection must be produced centrally by one projector. Rule implementations
must not mutate links directly.

### Gate 3: Measure effects

Run the same bounded search and recognition corpus with and without the semantic
projection. Record:

- number of distinct states before and after semantic deduplication;
- runtime and memory;
- number of rules requiring adaptation;
- amount of additional code in parser, formatter, matcher and explanation layers;
- whether provenance supports a concrete explanation such as combining the two
  occurrences in `a + a + b -> 2*a + b`;
- overlap or conflict with PR #241's AC-aware recognition.

### Gate 4: Review against acceptance criteria

Choose option C only if the spike demonstrates all of the following:

- semantic state reduction or substantially simpler AC rule logic;
- reliable bidirectional provenance for duplicate operands;
- no manual projection maintenance in ordinary rules;
- an incremental compatibility path for existing `Expr` rules;
- bounded memory and acceptable serialization complexity;
- a clear division of responsibility between semantic AST and e-graph.

Choose option D if semantic deduplication helps but durable subexpression
provenance does not justify a complete second AST.

Keep option A if the measured benefit is small compared with the migration and
cognitive cost.

Option B is rejected for the current project because preserving entered notation
and didactic steps is a core requirement.

### Gate 5: Record the final decision

After the spike, update this ADR in a dedicated PR:

- set `Status` to `Accepted` or `Rejected`;
- state the selected alternative;
- include measured results;
- list superseded components and migration stages;
- link follow-up implementation issues.

No production-wide migration may begin while this ADR remains `Proposed`.

## Preliminary recommendation

Proceed with the bounded spike for option C, while treating option D as the
fallback. Do not yet make two ASTs part of every public API.

The spike should prove that the projection layer earns its cost. In particular,
it must demonstrate something a canonical string or e-class alone cannot provide:
precise, bidirectional provenance between written occurrences and unordered
semantic operands.

## Proposed model boundaries

If option C is accepted, responsibilities are divided as follows.

### Syntax model

Owns:

- grouping and operand order;
- source spans and occurrence identity;
- entered notation;
- local tree positions;
- exact syntactic rewrite presentation.

### Semantic model

Owns:

- mathematical operators and operands;
- operation-specific laws explicitly declared by the domain;
- unordered multisets for associative and commutative operations;
- semantic equality and hashing;
- no source spans, display ordering or occurrence identity.

### Projection and provenance

Owns:

- many-to-many links between syntax occurrences and semantic references;
- link kinds such as `EXACT`, `CONTRIBUTES_TO`, `NORMALIZED_TO`, `ELIMINATED`
  and `INTRODUCED`;
- reverse lookup for highlighting and explanations;
- transformation provenance between old and new semantic states.

### Formatter

May choose a deterministic order for output. That order is a serialization and
presentation concern and must not participate in semantic equality.

### E-graph

Owns equivalence classes between semantic expressions or representations. It does
not replace syntax-to-semantics provenance. The spike must establish whether the
semantic AST is stored independently or represented as a typed view over e-nodes.

## Consequences if accepted

- `BinaryExpr` remains initially as the syntax representation.
- `ExpressionCanonicalizer` is no longer the long-term semantic data model; its
  flattening and bucket logic can inform the projector and deterministic formatter.
- canonical strings remain useful for persistence and diagnostics but cease to be
  the definition of semantic identity.
- rule APIs require an explicit classification: syntax rule, semantic rule or
  equivalence rule.
- ordinary rules receive framework-generated projection/provenance rather than
  constructing it themselves.
- migration proceeds operator by operator, starting with addition and only later
  multiplication.

## Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Two models double the concepts rule authors must learn | Keep current `Expr` APIs during the spike; expose semantic APIs only to rules that need them |
| Projection links become stale | Use immutable expressions and recreate projection atomically; no public mutators |
| Duplicate operands cannot be traced | Keep occurrence references only in the projection, excluded from semantic equality |
| Semantic and e-graph layers overlap | Define semantic structure versus equivalence-class responsibilities in Gate 4 |
| PR #241 duplicates AC behavior | Measure and document whether recognition profiles remain needed for syntax matching |
| Stable output accidentally becomes semantic ordering | Test equality across different construction and iteration orders |
| Migration becomes repository-wide too early | No production migration before this ADR is accepted |

## Non-goals

This ADR does not decide:

- that every operator is associative or commutative;
- that subtraction should be stored as an unordered operation;
- a concrete third-party multiset implementation;
- a repository-wide rewrite of existing rules;
- replacement of equality saturation;
- the final persistence format.

Those decisions require separate evidence or follow-up ADRs.
