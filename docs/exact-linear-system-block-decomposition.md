# Exact linear-system block decomposition

This slice of issue #721 consumes the certified `A*x=b` representation from
PR #722 and exposes independent structure inside its coefficient matrix.

## Mathematical model

The decomposer builds a bipartite incidence graph:

```text
equation rows  <->  variable columns
```

A row and a column are connected exactly when the corresponding rational
coefficient is non-zero. Connected components are independent because every
coefficient between two different components is exactly zero.

For example, a scalar system containing one pair of equations in `x,y` and a
second pair in `z,w` becomes two retained components. Row and column
permutations make the block structure explicit without changing the source
occurrences or pretending that matrix multiplication is commutative.

## Retained component kinds

The result distinguishes:

- `COUPLED_SUBSYSTEM` — at least one equation row and one variable column;
- `FREE_VARIABLES` — zero coefficient columns not connected to any equation;
- `CONSTANT_CONSTRAINTS` — zero coefficient rows containing only a constant
  condition.

A constant component with a non-zero right-hand side is marked as a localized
contradiction. Thus `x-x=0` remains a tautological row with a free `x`, while
`x-x=1` remains an inconsistent constant constraint. Neither case loses the
syntactically declared coordinate.

## Evidence and fail-closed behavior

`ExactLinearSystemBlockDecomposer` retains:

- the complete row and column partition;
- deterministic component order;
- deterministic row and column permutations;
- variable names and original row provenance per component;
- free-variable, constant-constraint and contradiction status;
- an explicit work ledger;
- an exact source-system fingerprint;
- a content-addressed component certificate;
- independent recomputation through `verify(...)`.

Every matrix entry is inspected while constructing the incidence graph and
again while checking that all cross-component coefficients are zero. Exhausted
work yields `BUDGET_INCONCLUSIVE`; a connected matrix yields
`DIRECT_REPRESENTATION_AVAILABLE` and no manufactured decomposition.

## Material capability

A successful result exposes a mechanically actionable partition into independent
linear subproblems. It is more than a display tag: every original row and
variable column is assigned exactly once, and the retained permutations define
how to obtain block order from the original exact matrix.

The result reports the following concrete capabilities when applicable:

```text
INDEPENDENT_LINEAR_SUBSYSTEMS
FREE_VARIABLE_COMPONENTS
CONSTANT_CONSTRAINT_COMPONENTS
INCONSISTENCY_LOCALIZATION
```

The next slice can use the component indices to construct exact submatrices and
run the same fixed solver or rule inventory independently on each block. A
matched-work comparison must still determine whether that reduces total search
work compared with direct scalar or full-matrix solving.

## Boundaries

This slice does not:

- invent a matrix factorization;
- reorder factors in a matrix/operator product;
- infer invertibility or spectral properties;
- recognize an eigenvalue problem;
- infer a quantum-physical interpretation;
- claim a performance advantage before the matched-work experiment.
