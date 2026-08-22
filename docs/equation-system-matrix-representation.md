# Exact equation-system to matrix representation

Issue #721 introduces a typed cross-representation bridge from a list of
scalar equations to an exact symbolic matrix equation.

## First executable slice

The implemented route is:

```text
InputType.SYSTEM
  -> List<Equation>
  -> bounded affine analysis over exact rational coefficients
  -> deterministic variable order
  -> exact coefficient matrix A, variable vector x and RHS b
  -> exact A*x=b representation
  -> scalar-row reconstruction
  -> independently recomputable certificate
```

For example:

```text
2*x + 3*y = 7
4*x - y = 5
```

is represented as:

```text
A = [[2, 3], [4, -1]]
x = [x, y]^T
b = [7, 5]^T
A*x = b
```

The relation is recorded as `SOLUTION_SET_EQUIVALENCE`, not ordinary AST
identity. The source equation order, each source-row expression and the
lexicographically deterministic variable order remain part of the retained
representation and certificate.

## Exact supported fragment

`LinearSystemRepresentationBridge` supports affine expressions built from:

- finite numeric literals;
- variables;
- addition and subtraction;
- multiplication when at least one factor is constant;
- division by an exact non-zero constant;
- bounded exact integer powers of constants;
- exponent one for a non-constant affine expression.

Numeric literals and constant arithmetic are represented by the existing
canonical `Rational` type. Products of two non-constant forms, variables in a
denominator and non-linear powers return `NONLINEAR`. Functions and undefined
constant operations return `DOMAIN_UNSUPPORTED`. Exhausted work returns
`BUDGET_INCONCLUSIVE`; none of these outcomes produces a guessed matrix.

## Retained representation

`ExactLinearSystem` retains:

- exact rational coefficient matrix;
- deterministic variable order;
- exact rational right-hand side;
- source equation index and text for every row;
- exact coefficient and augmented ranks;
- `UNIQUE`, `UNDERDETERMINED` or `INCONSISTENT` classification;
- square, more-variables or more-equations dimension shape;
- redundant-row count derived from the augmented rank.

Rectangular, redundant and inconsistent systems therefore remain represented
without being forced into the former square `double`-based demo model.

## Verification and work accounting

The bridge counts expression inspections, coefficient materialization,
rank-elimination operations and scalar-row reconstruction against one explicit
work budget. The ledger is balanced as:

```text
configured = consumed + remaining
```

A represented result contains a content-addressed certificate binding:

- bridge and certificate schema revisions;
- relation kind;
- every source equation;
- variable order;
- every exact coefficient and RHS value;
- exact ranks and solution classification.

`verify(...)` reruns the complete bounded analysis from the original scalar
system and requires exact equality of representation, certificate, relation,
status, detail code and work ledger. Tampered matrix data or stale evidence is
rejected.

## Architecture boundary

`RepresentationBridge` is deliberately separate from expression rewriting.
It defines explicit relation kinds such as expression equality, solution-set
equivalence, basis-change equivalence and model-interpretation candidates.
Consumers must use only consequences authorized by the retained relation.

This first slice does not yet:

- turn the alternate representation into an automatic search edge;
- synthesize hidden matrix products or block decompositions;
- expose an eigenvalue problem;
- infer a quantum-physical interpretation;
- replace the original scalar notation;
- establish that matrix representation reduces search work.

Those steps remain in #721. The next slice should compare recognition-only and
matrix-enabled execution against scalar search and a direct exact linear-system
solver before selecting the default preparation policy.
