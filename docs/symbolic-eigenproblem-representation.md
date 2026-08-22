# Symbolic linear systems and eigenproblem recognition

This slice of issue #721 extends exact equation-system representation beyond
numeric/rational coefficient matrices. It introduces an explicit distinction
between vector coordinates and scalar parameters so systems such as

```text
a*x + b*y = lambda*x
c*x + d*y = lambda*y
```

can be recognized as

```text
A * v = lambda * v
```

without guessing variable roles from names.

## Declared unknown roles

The source supplies an ordered list of unknown coordinates, for example:

```text
[x, y]
```

Every other polynomial symbol remains a scalar parameter. The example therefore
produces the shifted coefficient matrix

```text
[[a-lambda, b],
 [c, d-lambda]]
```

with scalar parameters `[a,b,c,d,lambda]`.

The declared coordinate order is retained exactly. It is not alphabetically
inferred, and names such as `psi`, `H` or `E` carry no mathematical or physical
role by themselves.

`SymbolicLinearSystemRepresentationBridge` supports exact polynomial
expressions, constant rational division and bounded non-negative powers. A term
with total degree greater than one in the declared unknown coordinates is
`NONLINEAR`. Functions and symbolic denominators are currently
`DOMAIN_UNSUPPORTED`. Every accepted row is reconstructed as a polynomial
identity before a certificate is emitted.

## Eigenproblem structure

`EigenproblemRepresentationBridge` recognizes the exact oriented pattern

```text
(A - lambda*I) * v = 0
```

when:

- the symbolic system is square;
- the right-hand side is exactly zero;
- the eigenvalue parameter occurs with coefficient `-1` on every diagonal;
- the eigenvalue parameter does not occur off diagonal;
- the vector is explicitly assumed non-zero.

The non-zero-vector condition is essential. Without it, every homogeneous
system has the trivial zero solution and cannot establish an eigenvalue
problem. Missing this condition returns `ASSUMPTION_REQUIRED`, never a guessed
candidate.

The initial matcher deliberately does not normalize the reverse row orientation
`lambda*v=A*v`. A later preparation rule may multiply rows by `-1` and replay the
same principal recognizer, retaining that work explicitly.

## Executable consequence

Recognition unlocks a bounded exact
`BoundedCharacteristicPolynomialSolver`. It computes

```text
det(A - lambda*I)
```

as an exact polynomial and emits the singularity condition

```text
det(A - lambda*I) = 0.
```

The initial implementation uses deterministic Laplace expansion and has an
explicit dimension bound and work budget. It is suitable for small
finite-dimensional pilot systems, not a claim of a scalable general determinant
backend.

## Quantum interpretation boundary

The exact mathematical decision and declared model metadata are independent:

```text
EIGENVALUE_PROBLEM_RECOGNIZED
DECLARED_QUANTUM_OPERATOR_MODEL_INTERPRETATION
DECLARED_HERMITIAN_SPECTRAL_MODEL
```

The first follows from exact algebraic structure. The latter two require an
explicit source model domain:

```text
GENERIC_LINEAR_ALGEBRA
FINITE_DIMENSIONAL_QUANTUM
```

The retained interpretation values are correspondingly named
`DECLARED_QUANTUM_OPERATOR` and
`DECLARED_HERMITIAN_QUANTUM_OBSERVABLE`. These statuses mean that the caller
provided the quantum-domain and operator-property declarations and that the
algebraic eigenproblem is compatible with them. They do not by themselves prove
that a concrete physical system realizes those declarations.

In the quantum domain, declared `HERMITIAN` operator metadata enables the
stronger declared Hermitian-observable model. In the generic domain, even an
expression written as `H*psi=E*psi` receives no quantum interpretation. The
software does not infer Hilbert-space meaning, observability or Hermiticity from
symbol spelling. Declared operator properties must pass a separate proof or
validation stage before a stronger evidential status is authorized.

## Evidence

The symbolic-system, eigenproblem and characteristic-polynomial stages each
retain:

- source and configuration identity;
- explicit coordinate/parameter roles;
- canonical polynomial matrices and vectors;
- relation or consequence kind;
- assumptions and model-domain declarations;
- declared operator properties without silently promoting them to proofs;
- configured, consumed and remaining work;
- content-addressed certificates;
- independent verification by complete recomputation.

## Current exactness boundary

Polynomial arithmetic is exact relative to the current parsed `NumberExpr`
value. Integer literals and rational expressions constructed from exact integer
literals retain the intended rational values. Decimal source text is currently
parsed through `double` before conversion to `Rational`; lexical decimal
exactness therefore remains part of the exact-scalar migration tracked by #661.
No stronger decimal claim is authorized by this slice.

## Non-goals

This slice does not yet provide:

- complex exact scalars;
- conjugation or adjoints;
- tensor products or commutators;
- general matrix/operator non-commutative expression matching;
- arbitrary row-orientation normalization;
- scalable determinant algorithms for large dimensions;
- automatic physical-model inference;
- proof of declared Hermiticity or other operator properties;
- a claim that the eigenproblem route outperforms every direct solver.
