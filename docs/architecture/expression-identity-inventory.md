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

### Parser allocation: occurrence-like objects without occurrence identity

`ExpressionParser` constructs nodes directly with `new` and folds additive and
multiplicative chains left-associatively. Repeated input such as `a + a` creates two
separate but equal `VariableExpr` objects.

The allocations resemble occurrences, but no stable occurrence ID or source span is
part of `Expr`. Object identity is therefore only incidental and cannot safely be
used as persisted occurrence identity.

Classification: **incidental allocation identity**, not an explicit contract.

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

`TreePosition` and local rewrite code identify a subtree by a child-index path.
Paths distinguish two equal occurrences inside one concrete binary tree and are
therefore essential for selecting one rewrite location.

A path is only meaningful relative to a particular root representation. AC
canonicalization or DAG sharing can invalidate the assumption that every value has
one unique path.

Classification: **occurrence identity relative to a concrete representation**.

### Stable hashes in search and mining

`ExpressionCanonicalizer.stableHash` is used across transformation guards, search
strategies, convergence analysis, graph clustering, telemetry and mining. This is
currently the broadest shared notion of mathematical sameness.

Architectural implication: changing canonical identity affects substantially more
than parsing. A replacement needs an adapter or compatibility phase rather than an
immediate switch of `Expr.equals`.

Classification: **cross-subsystem mathematical key**.

### E-graph/equality saturation

The e-graph represents broader equivalence under selected rewrite rules. E-class
membership can identify representations that are not collapsed by the ordinary
canonicalizer.

E-class identity is dynamic and theory-dependent. It is therefore not automatically
suitable as the ordinary identity of an immutable `Expr` value.

Classification: **bounded equivalence-class identity**.

### PR #241 recognition profiles

PR #241 introduces explicit, bounded associative/commutative recognition and an
`EquivalentExpressionProvider`. This operates at recognition time and deliberately
does not globally redefine `Expr.equals`.

Architectural implication: a future canonical value layer may reduce some AC
matching work, but syntax-pattern recognition and broader equivalence providers can
still remain necessary. The spike must measure overlap rather than assume
replacement.

Classification: **rule-specific matching equivalence**.

## Current identity matrix

| Mechanism | Mathematical value | Representation | Occurrence/use | Notation | Stable across serialization |
|---|---:|---:|---:|---:|---:|
| `Expr.equals` | partially | yes | no | grouping/order only | yes |
| Java `==` | no contract | allocation only | incidental | no | no |
| canonical string/hash | bounded yes | canonical representation | no | no | yes |
| `TreePosition` | no | relative to tree | yes, within one root | indirectly | only with same representation |
| e-class | theory-dependent yes | many representations | no | no | requires e-graph persistence/rebuild |
| PR #241 recognition | rule-dependent | matches variants | match-local | pattern-sensitive | profile is persistable |

## Confirmed gaps

1. There is no explicit type for mathematical value identity.
2. There is no explicit occurrence/use type shared by parser, rewrite and
   explanation layers.
3. Parser object identity is observable but not contractual.
4. `Expr.equals` conflates semantic leaves with syntax-shaped composite identity.
5. Canonical identity has no bidirectional provenance to source occurrences.
6. Tree paths assume a tree representation and cannot directly address multiple
   uses of one shared DAG value.
7. E-class identity and ordinary canonical identity have no formally documented
   boundary.
8. No factory currently guarantees that equal values are the same Java object.

## Initial migration constraints

- Do not change `Expr.equals` globally during the spike.
- Keep stable hashes available for existing search and mining call sites.
- Introduce experimental value/use types in an internal package.
- Preserve `TreePosition` for syntax-local rules until an occurrence adapter exists.
- Compare scoped interning with no interning; do not begin with an unbounded global
  pool.
- Require stable structural keys even if `==` becomes a factory-scoped fast path.

## Executable characterization

`ExpressionIdentityCharacterizationTest` records the current behaviour:

- equal variables are structurally equal but not interned;
- repeated written variables are distinct allocations;
- grouping and order affect `Expr.equals`;
- canonical hashes remove AC differences while retaining multiplicity;
- `BinaryExpr` already permits a manually shared child, proving that the Java object
  graph can be a DAG even though the parser currently creates a tree;
- record equality cannot distinguish one shared child from two equal occurrences.

These tests describe the baseline. Future spike tests will state the desired
identity laws without modifying this characterization silently.
