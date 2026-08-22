# Exact representation matched-work experiment

Issue #721 requires a controlled comparison before the project may claim that a
matrix or operator representation reduces useful search work. The comparison in
this slice is deliberately narrower than wall-clock benchmarking and stricter
than counting graph nodes from unrelated search tasks.

## Routes

The frozen configuration compares two independently implemented routes:

```text
REPRESENTATION_RREF_V1
  scalar equation ASTs
  -> exact A*x=b representation
  -> exact reduced row echelon form
  -> canonical verified consequence

DIRECT_SCALAR_ELIMINATION_V1
  scalar equation ASTs
  -> sparse named-coefficient equations
  -> direct substitution/elimination
  -> canonical verified consequence
```

The direct route does not construct `ExactLinearSystem`, a coefficient matrix or
`ExactRrefReduction`. It retains scalar equations as maps from declared variable
names to exact rational coefficients. It selects variables in the declared
order, normalizes one scalar equation, and substitutes that equation into every
other scalar equation.

The old product behavior that submitted each equation as an unrelated search
root is not a baseline. It does not solve the same system-level task and would
make any comparison meaningless.

## Identical accepted fragment

Both routes receive the same already parsed equation ASTs and declared variable
order. Their exact affine fragment has the same power boundary:

- exponent one preserves an affine base;
- every other exponent requires a constant base;
- the exponent must be an exact integer with bounded absolute value;
- negative exponents are allowed only for non-zero constant bases;
- `0^0` and zero to a negative exponent are unsupported;
- a variable base such as `x^0` remains nonlinear rather than becoming one.

The cross-route tests characterize these cases directly. A case outside either
route's common fragment is not eligible for a matched-work result.

## Identical terminal obligation

A case is comparable only if both routes independently verify the identical
`ExactLinearSolutionConsequence`:

```text
UNIQUE
  exact particular solution

UNDERDETERMINED
  canonical particular solution
  canonical nullspace basis

INCONSISTENT
  normalized contradiction 0 = 1
```

Variable order is part of the consequence. The canonical particular solution
sets every free coordinate to zero. Each nullspace basis vector is the identity
on one free coordinate and zero on all other free coordinates. This makes exact
route-to-route equality meaningful rather than relying on numerical sampling or
an isomorphism guessed after the experiment.

## Equal budget

Both routes consume the same already parsed ASTs. Parsing is common input
preparation and is deliberately outside the route comparison; neither route is
credited or charged for it.

After that common boundary, each route receives one total primitive-work budget
and cannot reset it between analysis, representation, elimination, consequence
formation and internal evidence production.

For the representation route:

```text
representation work + RREF work <= route budget
```

The RREF stage receives only the remaining work after exact system
representation.

For the direct route:

```text
source analysis + scalar elimination + internal evidence <= route budget
```

Budget exhaustion is `BUDGET_INCONCLUSIVE` and retains no partial mathematical
claim.

## Work units

The experiment uses deterministic mechanical work units, not elapsed time.
Existing accepted ledgers remain authoritative for the representation and RREF
stages. The direct route counts matching kinds of finite work:

- AST node inspection and exact affine extraction;
- pivot or coefficient inspection;
- exact coefficient and right-hand-side updates;
- equation swaps, scaling and substitution;
- operation replay;
- exact particular-solution and nullspace verification.

The report exports source-stage, consequence-stage and total work for both
routes. The direct route additionally retains its source-analysis, elimination
and evidence split in the JSON stage details.

A work unit is an implementation-defined algorithmic accounting unit, not a CPU
instruction or a theorem about intrinsic complexity. Its value is reproducible
comparison under this frozen implementation and configuration. Wall-clock
measurements may be added as secondary observations but cannot replace this
ledger.

## Frozen cases

The initial configuration retains:

1. dense unique 2x2 system;
2. redundant underdetermined 2x3 system;
3. inconsistent duplicate-left-side system;
4. block-diagonal 3x3 system;
5. two interleaved independent 2x2 blocks;
6. exact rational diagonal system;
7. cancelled free coordinate `x-x=0`;
8. cancelled-coordinate contradiction `x-x=1`.

The cancelled-coordinate cases are mandatory because they distinguish the
syntactic coordinate space from non-zero coefficients. A baseline that silently
removes `x` before solving would not be solving the same problem.

## Retained evidence

The Gradle task

```text
:regelsuche-experiments:representationMatchedWork
```

writes:

```text
build/reports/representation-matched-work/matched-work-report.json
build/reports/representation-matched-work/matched-work-report.md
```

The experiments module `check` task and the root `check` task depend on this
task. The generated report contains:

- schema and configuration identity;
- total budget per route;
- exact input and declared variable order;
- route status and detail codes;
- independent verification outcome;
- source, consequence and total work;
- canonical terminal consequence;
- work delta and observed case winner;
- aggregate representation/direct/tie counts;
- explicit claim boundary.

No timestamp is included, so identical code and inputs produce byte-identical
reports.

## Claim boundary

A fully green report establishes only the following:

- both routes solved the same frozen tasks;
- both produced and independently verified identical exact consequences;
- the reported deterministic work counts follow their frozen ledgers;
- the observed per-case differences are reproducible for this configuration.

It does **not** establish:

- wall-clock superiority;
- asymptotic superiority;
- superiority for arbitrary linear systems;
- superiority over established numerical or symbolic solvers;
- global rewrite-search superiority;
- external novelty;
- a benefit for matrix operations not exercised by this case set.

Any broader statement requires a larger preregistered corpus, retained raw
reports and a separate statistical or complexity argument.
