# Exact RREF capability frontier

Issue #721 requires alternate representations to unlock a material, executable
capability rather than merely attach a structural label. This slice adds an
exact, bounded and certificate-carrying Gauss-Jordan solver for the authoritative
`ExactLinearSystem` representation.

## Product route

The normal `system` path now has the following bounded stages:

```text
scalar equations
  -> exact A*x=b representation
  -> exact rank and consistency classification
  -> independent block recognition
  -> exact reduced row echelon form
  -> unique solution, affine parametric solution or contradiction witness
```

Failure of the RREF work budget does not erase the earlier exact matrix
representation. The product output retains the successful earlier stage and
reports the RREF attempt as `BUDGET_INCONCLUSIVE`.

## Exact relation and replay

The reduction declares

```text
SOLUTION_SET_EQUIVALENCE
```

rather than ordinary expression equality. Every successful result retains an
ordered list of invertible elementary row operations:

```text
swap(i, j)
scale(i, nonZeroScalar)
add(target, source, scalar)
```

The solver independently replays this list against the original augmented
matrix and requires the replayed matrix to equal the reported RREF exactly.
Scaling by zero is impossible, matrix columns are never reordered and no scalar
commutativity rule is reused as a matrix rule.

## Deterministic bounded algorithm

The implementation uses deterministic Gauss-Jordan elimination:

1. scan columns from left to right, including the augmented column;
2. choose the first non-zero candidate row at or below the current pivot row;
3. swap it into place when necessary;
4. normalize the pivot to exact one;
5. eliminate that column from every other row;
6. validate the complete reduced-row-echelon invariants;
7. replay every retained row operation from the source matrix.

All arithmetic uses exact `Rational` values. Matrix reads, pivot scans, semantic
row operations, RREF validation, replay and final equality checks consume an
explicit work ledger. Exhaustion produces no partial reduction or certificate.

## Executable solution consequences

For a consistent system, the RREF exposes an exact affine solution space.

For a unique system it emits a particular solution such as

```text
[x=2, y=1]
```

For an underdetermined system it emits

```text
particular solution + span(nullspace basis)
```

For example,

```text
x + y + z = 2
2*x + 2*y + 2*z = 4
```

produces the particular solution

```text
[2, 0, 0]^T
```

and nullspace basis

```text
[-1, 1, 0]^T
[-1, 0, 1]^T.
```

For an inconsistent system, the full augmented RREF retains a row such as

```text
[0, 0 | 1]
```

as a concrete contradiction witness. It does not manufacture a solution or
nullspace parameterization for the empty solution set.

## Capability frontier

The result records the exact ordered capability delta.

Before RREF:

```text
EXACT_LINEAR_SYSTEM_REPRESENTED
EXACT_RANK_CLASSIFICATION_AVAILABLE
```

Always newly unlocked after successful RREF:

```text
EXACT_RREF_AVAILABLE
REPLAYABLE_ELEMENTARY_ROW_OPERATIONS
```

Depending on the solution classification, one of the following capability sets
is also unlocked:

```text
EXACT_AFFINE_SOLUTION_SPACE_AVAILABLE
EXACT_UNIQUE_SOLUTION_AVAILABLE

EXACT_AFFINE_SOLUTION_SPACE_AVAILABLE
EXACT_PARAMETRIC_SOLUTION_AVAILABLE

EXACT_INCONSISTENCY_WITNESS_AVAILABLE
```

The frontier verifies that `newlyUnlocked` and `lostOrConditional` are the exact
ordered set differences between the before and after inventories. This provides
the first direct capability evidence required by #721; it is not yet the full
matched-work experiment.

## Certificate

A successful result retains:

- solver and certificate schema identity;
- source-system hash including variables, rows, right-hand side, provenance,
  ranks and classification;
- exact reduced augmented matrix;
- canonical elementary row-operation sequence;
- pivot and free-variable columns;
- contradiction rows;
- particular solution and nullspace basis when applicable;
- capability inventories before and after the reduction;
- newly unlocked and lost/conditional capabilities;
- configured, consumed and remaining work in the enclosing result;
- a SHA-256 content hash.

`verify(...)` recomputes the complete deterministic result with the originally
configured budget. Certificate, operation, solution or capability tampering is
therefore rejected.

## Boundaries

This slice does not claim:

- that Gauss-Jordan elimination is the cheapest solver for every system;
- numerical stability results for floating-point arithmetic;
- sparse or large-scale linear-algebra performance;
- arbitrary matrix factorization;
- eigenvalue or quantum interpretation;
- a global search-work advantage.

Those comparisons remain part of the matched-work study in #721. The current
claim is narrower: an exact system representation now unlocks an executable,
replayable solution or contradiction capability with explicit finite work and
independent verification.
