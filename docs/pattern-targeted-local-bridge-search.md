# Pattern-targeted local bridge search

Issue #718 adds the bounded fallback after direct matching, theory-aware
matching and the specialized exact preparation solvers from #708.

The planner does not receive a desired result expression. Its only terminal
predicate is:

```text
one declared visible principal applicability schema matches completely
AND
the concrete principal implementation replays on that exact retained AST
```

This is a rule-directed applicability search, not target-directed expression
search.

## Execution order

The intended product order remains:

```text
direct concrete replay
  -> theory/equivalent-representative recognition
  -> specialized exact residual solver
  -> bounded pattern-targeted local bridge fallback
```

The fallback is deliberately more expensive than the earlier stages and must
not replace exact polynomial quotient, AC factor exposure, common-monomial,
perfect-square or common-denominator solvers.

## First supported boundary

The first revision accepts only a `PatternRewriteRule` as principal rule. This
provides an explicit source pattern and recognition profile. An algorithmic Java
rule without such a schema returns:

```text
UNSUPPORTED
```

The planner never infers an applicability schema from a rule ID, implementation
class, example or benchmark fixture.

The frozen preparation inventory:

- excludes the principal rule itself;
- admits only equivalence-preserving transformations;
- retains the inventory and principal-rule content hashes;
- accumulates normalized assumptions in the state identity;
- counts every composite edge by its retained primitive rule IDs.

## Search semantics

Search proceeds breadth first, so the accepted bridge has minimum preparation
transition depth inside the frozen local space. Candidates within one depth are
ordered deterministically by:

1. full principal match;
2. more matched principal-pattern nodes;
3. more consistent placeholder bindings;
4. fewer residual obligations;
5. lower AST growth;
6. lower cumulative primitive work;
7. exact structural and rule/application identities.

This ordering chooses among equal-depth alternatives but does not skip an
in-budget state.

A state consists of the parsed AST structure and normalized assumptions. The
recursive structure hash includes node kind, operator, child order, function
name and arity, variable names and exact number bits. Pretty-printed or merely
algebraically canonical text is not the retained identity.

## Bounded outcomes

```text
DIRECT_MATCH_AVAILABLE
PREPARED
NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE
BUDGET_INCONCLUSIVE
UNSUPPORTED
INVALID_CERTIFICATE
```

The configured bounds cover depth, visited states, generated transitions,
primitive preparation work, expression nodes, retained successors and matcher
work. A reached mechanical limit is never reported as a mathematical non-match.
Only a completely exhausted frozen local closure may emit
`NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE`.

Every generated transition receives one work-ledger disposition, including
principal-rule exclusion, unsafe-rule rejection, duplicates, expression/work
limits, successor truncation, depth/state boundaries and deterministic
short-circuiting after the selected terminal match.

## Replay and certificate

A prepared result retains:

- source, terminal prepared and principal result expressions;
- initial and final assumptions;
- initial and terminal match analyses;
- every preparation transformation and primitive rule ID;
- the concrete principal replay;
- principal and preparation-inventory hashes;
- the complete budget and balanced work ledger;
- a content-addressed certificate.

`verify(...)` checks the certificate identities and then reruns the complete
bounded deterministic plan. Changed paths, assumptions, work, inventory,
principal result or certificate fail closed.

## First SymPy amplification pilot

The first characterization uses the independently reimplemented SymPy/Fu
Pythagorean rule:

```text
sin(X)^2 + cos(X)^2 -> 1
```

The preparation inventory contains only the general neutral-element rule:

```text
A * 1 -> A
```

The frozen cases are:

| Input | Direct principal match | Local bridge | Expected result |
| --- | ---: | ---: | --- |
| `sin(x)^2 + cos(x)^2` | yes | 0 steps | `1` |
| `(sin(x) * 1)^2 + cos(x)^2` | no | 1 step | `1` |
| `((sin(x) * 1) * 1)^2 + cos(x)^2` | no | 2 steps | `1` |
| `((sin(x) * 1) * 1)^2 + cos(y)^2` | no | complete closure has no bridge | unchanged |

The two additional positive cases do not introduce a benchmark-shaped
trigonometric helper. They demonstrate that one general preparation rule
increases the reachable application domain of an existing SymPy-derived
principal rule, while the different-argument near miss remains negative.

This is the first amplification characterization, not yet a corpus-wide SymPy
ranking. A later matrix must group many direct and legally obscured examples by
principal rule and report the incremental contribution of direct matching,
theory matching, exact preparation and local bridge search separately.

## Claim boundary

This slice may establish a shortest, replayable principal-applicability bridge
inside one finite declared local rewrite space. It does not establish global
reachability, mathematical novelty, universal simplification quality,
completeness, optimal proofs or superiority over SymPy as a whole.
