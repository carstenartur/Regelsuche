# Equation systems as one mathematical object

The normal command-line `system` path now preserves an equation system as one
mathematical object instead of submitting every scalar equation as an unrelated
search root.

## Default execution

```text
system "x = 1; y = 2"
```

executes:

```text
parse complete equation system
  -> exact scalar-system to A*x=b representation
  -> exact rank and solution classification
  -> exact independent-block recognition
  -> deterministic human-readable report
```

The report includes:

- the relation kind (`SOLUTION_SET_EQUIVALENCE`);
- exact coefficient matrix `A`;
- deterministic variable vector `x`;
- exact right-hand side `b`;
- coefficient and augmented ranks;
- unique, underdetermined or inconsistent classification;
- independent coupled components, free coordinates and constant constraints;
- unlocked capabilities;
- representation and decomposition work ledgers.

A nonlinear or unsupported system produces its exact fail-closed terminal status
and detail code. It is not silently split into scalar searches and is not guessed
into a linear representation.

## Architecture decision

There is no external compatibility requirement for the former system behavior.
Consequently, `InputType.SYSTEM` uses the new exact representation service as
the product default. The old behavior of independently searching each scalar
equation is not retained as an implicit fallback.

This is distinct from historical evidence compatibility. Existing retained
experiments keep their exact configuration identities, but those records do not
control the default behavior of new CLI or Workbench executions.

## Current boundary

This product path currently supports exact rational linear systems under the
merged representation contract. It exposes certified block structure where
available. It does not yet:

- accept a declaration separating unknown coordinates from symbolic parameters;
- recognize `A*v=lambda*v` with symbolic matrix entries;
- infer a quantum interpretation;
- construct hidden matrix products;
- claim that the matrix route uses less work than every direct solver.

The next domain slice in #721 should add typed unknown/parameter roles and then
recognize an exact finite-dimensional eigenproblem while keeping the mathematical
structure decision separate from any quantum-physical model interpretation.
