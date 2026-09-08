# Exact matrix/operator preparation and Workbench replay

Issue #746 adds bounded preparation to the exact system, block, RREF and
eigenproblem contracts delivered by #721. The Workbench links to
`/static/representations.html`. It accepts scalar equations or an explicitly
declared matrix equation, retains the original notation, and shows alternative
representations with complete exact replay.

## Source-backed alternatives

For the declared coordinate order `[x,y]`, the source

```text
2*(x+y)+3*(x-y)=5
(x+y)+4*(x-y)=6
```

exposes the repeated forms `x+y` and `x-y`. The retained ordered product is

```text
F = [[2,3],[1,4]]
G = [[1,1],[1,-1]]
F*G = [[5,-1],[5,-3]]
```

The verifier compares every entry of `F*G` with the source coefficient matrix.
A separate concrete principal applies `G` and then `F` to the coordinate
vector and checks every reconstructed source row. Source paths, factor order,
dimensions, coordinates, and both certificates remain in the artifact.

The finite proposal inventory contains the direct matrix, repeated homogeneous
linear source forms, visibly additive identity terms, certified rational-system
blocks, explicitly supplied matrix expressions, and ordered pairs from the
visible matrix catalog. A general non-unique matrix factorization is outside
this contract. Identity-plus structure is proposed from visible source terms
or catalog entries; an arbitrary residual `A-I` is not synthesized for every
matrix. Rational block exposure restores the complete row and column
permutations. Existing zero-row/free-column components remain in the system
block result, but do not produce empty matrix factors.

## Profiles and exact domain

| Profile | Retained behavior |
| --- | --- |
| `RECOGNITION_ONLY_V1` | Exact scalar/matrix formation and explicitly requested eigenproblem recognition; no preparation or downstream solving |
| `SAFE_PREPARED_REPRESENTATION_V1` | Source/catalog proposals, complete matrix and vector replay, rational RREF and eligible characteristic polynomial |
| `EXPERIMENTAL_OPERATOR_V1` | Safe profile plus explicit ordered operator recipes and a finite guarded rule pack |

The ordered AST is separate from scalar `Expr`. Products retain their order;
the rule pack supports associativity, left/right identities, left/right
distributivity, and inverse cancellation. It never imports scalar
commutativity. `inverse(T)` requires a supplied exact matrix witness, and both
`T*inverse(T)=I` and `inverse(T)*T=I` are checked before any rewrite. A declared
invertibility flag or a suggestive symbol name cannot satisfy this guard.
Operator recipes and matrix input using inverses require the experimental
profile. Unproved or ill-dimensioned proposals cannot be accepted.

Scalar entries use existing exact rational polynomials. Numeric literals,
including finite decimals, remain exact. Coordinates are explicitly declared
and ordered; other symbols are scalar parameters. The system must be affine in
the coordinates. Functions, nonconstant denominators, undefined constant
operations, and unguarded symbolic zeroth powers are outside the new profile.
The current bounds are 16 rows/columns, eight catalog matrices, eight operator
recipes, polynomial degree 32, 256 terms, 2048-bit rational coefficients, source
powers 0–20, and expression depth 32. Each scalar input has at most 4096
characters and 256 operators. Exceeding an exact-fragment limit is reported
explicitly; exhausting configured work returns `BUDGET_INCONCLUSIVE` and
retains already verified results. Neither outcome establishes non-existence.

## Typed integration and semantic identity

`RepresentationPreparation` in core coordinates a typed formation bridge and
a concrete downstream principal under one construction budget.
`UnifiedRulePreparationCoordinator.prepareRepresentation` exposes that same
coordinator to the preparation successor #745, without a search-to-math module
dependency. Consumers explicitly select acceptable formation relations. The
formation and principal result retain their own relation, certificate, and
work ledger. The matrix product application uses this same core coordinator.

| Evidence | Relation |
| --- | --- |
| Scalar affine system to matrix equation / RREF consequence | `SOLUTION_SET_EQUIVALENCE` |
| Complete ordered matrix identity and vector replay | `LINEAR_MAP_REPRESENTATION_EQUIVALENCE` |
| Guarded eigenproblem recognition (existing #721 contract) | `LINEAR_MAP_REPRESENTATION_EQUIVALENCE` |
| Characteristic-polynomial singularity condition | `SPECTRAL_OR_SIMILARITY_RELATION` |
| Explicit future basis conversion | `BASIS_CHANGE_EQUIVALENCE` remains distinct |
| Explicit model metadata | `MODEL_INTERPRETATION_CANDIDATE` remains distinct |

The coordinator does not merge these objects into scalar expression equality
or a shared scalar cache. Matrix evaluation memoizes only the same immutable
AST object inside one analysis. Catalog, coordinate order, source rows,
formation root and inverse witnesses are bound into certificate material.
This entry point delivers the typed representation tranche of #745; its other
scalar preparation work remains separate.

## Workbench, CLI and HTTP

The Workbench offers source/matrix navigation, declared coordinate order,
source-row mapping, assumptions, capability deltas, and certificate status.
Recognition, downstream RREF/characteristic-polynomial solving and model
interpretation have separate displays. An eigenproblem requires an explicit
eigenvalue parameter and nonzero-vector condition. The oriented recognizer
from #721 retains its existing exact scope.

Export downloads the complete artifact. Import and **Replay prüfen** submit it
to the server for a complete recomputation from the retained request. The
artifact remains replayable after restarting the server. Every retained field
must match; an attacker-supplied digest alone is never accepted as evidence.

The same versioned contracts are available through:

```bash
./gradlew :app:installDist
app/build/install/app/bin/app representations analyze \
  docs/examples/matrix-preparation-source-product.json > representation.json
app/build/install/app/bin/app representations replay representation.json
```

`POST /api/representations` accepts the request;
`POST /api/representations/replay` accepts the complete artifact and returns
`X-Representation-Replay: VERIFIED` on success. Invalid requests return 400,
altered replay evidence 409, and bodies beyond the shared HTTP limit 413.
The [request schema](schemas/regelsuche-matrix-preparation-request-v1.schema.json),
[artifact envelope schema](schemas/regelsuche-matrix-preparation-artifact-v1.schema.json)
and Workbench OpenAPI reference describe the wire contracts. Full replay
provides the semantic validation beyond the envelope schema.

## Further exact domains: explicit decision

The concrete use cases here are repeated linear forms, independent rational
blocks, visible matrix equations and generic polynomial eigenproblems. They
do not require a new complex or quantum scalar domain. Consequently this
version **defers** exact complex scalars, conjugate transpose, tensors,
commutators and density matrices. No physics is inferred from `H`, `psi`,
`lambda`, or any other name; this product request emits physical interpretation
`NONE`. Extra fields such as `hermitian` are rejected.

A future addition needs its own versioned scalar/domain and relation contract,
a concrete consuming principal, exact positive evidence and negative controls.
For example, an adjoint needs explicit complex conjugation, tensor products
need ordered factor dimensions, and density-matrix relations need explicit
trace/positivity obligations. A label must never stand in for these checks.
This decision preserves the existing low-level #721 model metadata contract
without advertising unimplemented physical reasoning in the new surface.

## Qualification and reproduction

`MatrixPreparationQualification` defines twelve finite cases: repeated source
forms, mapped blocks, identity-plus, unused coordinates, inconsistency, exact
decimals, a nonlinear negative control, visible factors, ordered distributivity,
guarded inverse cancellation, eigenproblem recognition and its missing-vector
guard. Each retains all three profiles, for 36 complete artifacts plus
`qualification.json`. Explicit experimental recipes are recorded separately;
the source, coordinate order and catalog are common across profiles.

The independent `DIRECT_SCALAR_ELIMINATION_V1` baseline uses the same total
construction budget. Its unique/parametric/inconsistent solution consequences
must exactly equal both solving profiles whenever the baseline can solve the
case. Symbolic cases outside its exact fragment are labelled accordingly.
Profile reports compare canonical construction work and newly unlocked
capabilities. Both routes exclude separately bounded independent audit reruns
from their construction ledgers. These are finite correctness and capability
results, not elapsed-time measurements or a universal speedup claim. The frozen
`REPRESENTATION_RREF_V1` experiment and its retained evidence stay unchanged.

From a clean checkout with Java 25 and Docker:

```bash
./gradlew :regelsuche-math-algorithms:matrixPreparationEvidence
./gradlew verifyMatrixPreparationReproduction
./gradlew :app:e2eTest --tests '*MatrixRepresentationBrowserTest'
./gradlew ciCheck
mvn -B --no-transfer-progress -Pfull verify
```

Host artifacts are written to `build/reports/matrix-preparation/host`.
The container run reuses `Dockerfile.target-free-held-out-reproduction`, whose
Temurin base is pinned by SHA-256, and runs the exact qualification class from
the built immutable image ID as its existing nonroot user with networking
disabled. It writes `build/reports/matrix-preparation/container`. Verification
requires identical complete inventories and every byte to match both the
checkout rerun and container run. Root `check` includes this gate; normal CI
retains the reports. Host/container output folders are reset before generation
so stale files cannot satisfy the comparison.

Focused regression tests cover noncommutativity, every matrix cell, dimensions,
permutations, false inverse witnesses, unsupported domains, exhausted budgets,
relation separation, tampered artifacts, restart replay and responsive browser
navigation. The complete Maven/Docker and checkout-owned `ciCheck` gates remain
the integration authority.
