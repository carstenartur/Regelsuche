# Native polynomial simplification

`POLYNOMIAL_SIMPLIFICATION_V1` connects native exact arithmetic, normalization,
common-factor grouping and univariate factorization to the existing typed task
frontier. Both primitive and checked-learned execution use these same capabilities.
There is no SymPy production dependency and no reference answer in a query.

```java
var inventory = TypedLearnedMoveInventory.primitives(genome);
var solver = inventory.newTaskSolverWithProfile(
    TaskConstructionProfile.POLYNOMIAL_SIMPLIFICATION_V1);
var budget = new MoveSearch.Budget(8, 8, 1_000_000, 32, 1_000_000, 64);
var source = new ExpressionParser().parseExactTerm("(x+y)*(x-y)+y*(y+2)").expression();
var result = solver.simplify(source, List.of(), budget);
if (result.withinBudget()) {
    System.out.println(ExpressionFormatter.format(result.incumbent().expression()));
}
```

The result minimizes the existing arithmetic-operation surface objective among
admitted states. Ties retain the actual input. A completed bounded search does not
prove global minimality. `withinBudget()` includes final selected-path replay;
an overrun remains diagnostic and must not be counted as a successful request.

Use `newTaskSolverWithProfile(model, profile)` to prepare proved schemata with
the same native fallback. Existing `CumulativeTaskKnowledge.empty(genome, profile)`
also stores and restores the profile and can use it for declared task goals and
learning. Default and historical profiles keep their previous behavior.

Every admitted native proposal and supplied inventory edge is checked in the existing
total rational-polynomial domain, with the existing formal convention `0^0 = 1`.
Variable denominators, undefined intermediate divisions and malformed symbol
identities remain unsupported. A theorem for another source does not authorize
an edge. Exact product checks are distinct from independent irreducibility or
Lean proofs; this profile does not claim either of those stronger certificates.

The finite procedures retain their bounds: 16 native proposals per procedure,
32 terms for common-factor grouping, degree 16 and 256-bit coefficients for
native univariate factorization. The task source domain has its existing separate
bounds. Failed procedures, parsing, normalization and replay retain their declared
logical work. Logical work is not a CPU instruction count or a heap measurement.

## Reproduce the comparison

The original external comparisons remain frozen. The new declared protocol is
`config/benchmarks/native-polynomial-v1.json`: 24 historical polynomial inputs,
three unsupported controls and 48 additional public variants, three profiles and
three rotated repetitions. The native learned arm actually acquires schemata,
persists their bytes, exits, and reproves them in a fresh consumer JVM. The fixed
SymPy 1.14.0 portfolio pays identity/simplify/factor/cancel/horner attempts.

With Java 25 and the existing comparison prerequisites:

```sh
./gradlew -I scripts/external-polynomial-classpath.gradle :regelsuche-learning:externalPolynomialClasspath
PYTHONPATH=scripts python3 -m external_polynomial_comparison.run_native \
  --classpath regelsuche-learning/build/external-polynomial-classpath.txt \
  --output build/reports/native-polynomial-comparison
PYTHONPATH=scripts python3 -m external_polynomial_comparison.run_native --verify \
  --output build/reports/native-polynomial-comparison
```

The runner snapshots the actual runtime and launches it from those retained bytes.
It retains complete matrices, failed requests, source-bound selected paths,
independent exact Python audits, setup/restore operations and process receipts.
Each sequence's wall/worker CPU includes startup, acquisition where applicable,
persistence, queries, audits and shutdown. Controller CPU is separate; RSS is the
maximum process peak. Shared runtime retention and final evidence export are
outside those per-profile sequence boundaries. These are public development
observations, not a sealed family holdout or a universal CAS speed ranking.

The [2026-11-06 observation](research/native-polynomial-parity-2026-11-06.md)
reports per-case quality, complete sequence resources and exact source/runtime
bindings, including the remaining runtime gap.
