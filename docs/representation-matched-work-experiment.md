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

## Identical visible information

Both routes receive the same already parsed equation ASTs and the same declared
variable order. Neither route may accept that order on trust:

- both traverse every source AST to discover all syntactically present
  variables;
- both derive the deterministic lexicographic variable order;
- the direct route fails closed when the declared order or set differs;
- the representation route must produce exactly the declared order before the
  case can continue;
- this source-variable analysis is included in each route's work ledger.

This prevents the direct baseline from receiving a free variable inventory that
the representation route has to discover mechanically. Coordinates that occur
only in cancelled syntax, such as `x` in `x-x=0`, remain visible to both routes.
An extra coordinate absent from the source is outside this shared experiment
fragment.

Parsing is common input preparation and is deliberately outside the route
comparison; neither route is credited or charged for it.

## Identical accepted fragment

The exact affine fragment has the same power boundary in both routes:

- exponent one preserves an affine base;
- every other exponent requires a constant base;
- the exponent must be an exact integer with absolute value at most 64;
- negative exponents are allowed only for non-zero constant bases;
- `0^0` and zero to a negative exponent are unsupported;
- a variable base such as `x^0` remains nonlinear rather than becoming one.

Cross-route tests characterize `x^0`, `0^0`, a negative zero power, a valid
negative constant power, a composite exponent equal to one, exponent 21 and the
shared rejection boundary at exponent 65. A case outside either route's common
fragment is not eligible for a matched-work result.

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

After the common parsing boundary, each route receives one total
primitive-work budget and cannot reset it between source analysis,
representation, elimination, consequence formation and internal evidence
production.

For the representation route:

```text
source variable discovery + affine representation + rank classification
+ RREF formation and evidence <= route budget
```

The RREF stage receives only the remaining work after exact system
representation.

For the direct route:

```text
source variable discovery + affine analysis + scalar elimination
+ internal evidence <= route budget
```

Budget exhaustion is `BUDGET_INCONCLUSIVE` and retains no partial mathematical
claim.

## Work units

The experiment uses deterministic mechanical work units, not elapsed time.
Existing accepted ledgers remain authoritative for the representation and RREF
stages. The direct route counts corresponding finite work categories:

- source AST and variable inspection;
- exact affine extraction;
- pivot or coefficient inspection;
- exact coefficient and right-hand-side updates;
- equation swaps, scaling and substitution;
- operation replay;
- exact particular-solution and nullspace verification.

The report exports source-stage, consequence-stage and total work for both
routes. The direct route additionally retains its source-analysis, elimination
and evidence split in the JSON stage details.

A work unit is an implementation-defined algorithmic accounting unit, not a CPU
instruction or a theorem about intrinsic complexity. A lower count is evidence
only under the frozen ledgers and case set. Wall-clock measurements may be added
as secondary observations but cannot replace this ledger.

## Frozen cases

The initial configuration retains:

1. dense unique 2x2 system;
2. redundant underdetermined 2x3 system;
3. inconsistent duplicate-left-side system;
4. sparse diagonal 3x3 system;
5. two interleaved independent 2x2 structures;
6. exact rational diagonal system;
7. cancelled free coordinate `x-x=0`;
8. cancelled-coordinate contradiction `x-x=1`.

The sparse and interleaved cases exercise the same two frozen solving routes.
This experiment does **not** invoke the separate block decomposer and therefore
must not be cited as evidence that block decomposition reduces work.

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
reports. The surrounding CI artifact binds the generated files to the exact
repository revision that produced them.

## Claim boundary

A fully green report establishes only the following:

- both routes solved the same frozen tasks from the same visible information;
- both produced and independently verified identical exact consequences;
- the reported deterministic work counts follow their frozen ledgers;
- the observed per-case differences are reproducible for this configuration.

It does **not** establish:

- wall-clock superiority;
- asymptotic superiority;
- superiority for arbitrary linear systems;
- superiority over established numerical or symbolic solvers;
- a benefit from block decomposition;
- global rewrite-search superiority;
- external novelty;
- a benefit for matrix operations not exercised by this case set.

Any broader statement requires a larger preregistered corpus, retained raw
reports and a separate statistical or complexity argument.
