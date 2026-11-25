# Exact local Boolean constraint counting

## Contract and status

`FiniteConstraintCounter` sums a product of local nonnegative integer factors over all Boolean assignments consistent with `Problem.fixed`. A factor table uses bit `i` for its sorted scope's `i`th variable. Zero/one tables describe forbidden/allowed configurations; larger weights give weighted counts. Empty products equal one. Every unfixed variable is summed over, including variables absent from all factors.

The implementation performs bounded variable elimination using the minimum remaining degree by default. Ties use the original variable ID. The strategy overload also supports minimum fill (ties by remaining degree, then ID) and ascending IDs for comparisons. Graph construction, order selection and edge updates share the counting budget. Certificates keep the original variable IDs. It returns an exact `BigInteger` count, a source-bound elimination trace, and a positive-weight assignment when the count is positive. `FiniteConstraintVerifier` independently conditions the original tables and replays each local sum, checks all buckets and remaining scalar factors, and checks the witness against the original problem. The producer invokes this verifier before returning `EXACT`.

Resource exhaustion returns `UNAVAILABLE` with no count or certificate. It never means zero solutions. Work, cancellation, interruption, wall time, retained table cells, intermediate width and integer bit lengths are bounded. Input factors have at most 20 variables; actual admissible table sizes also depend on the state budget. The shared hard ceiling is one million retained cells and 65,536 integer bits. Elimination can be exponential in induced width; the API makes no general polynomial-time or optimal-order claim. It does not implement a SAT asymptotic theorem or optional generating-polynomial refinements.

## Reproducible example

For three variables `a,b,c`, use factors with scopes `[0,1]` and `[1,2]`, both with table `[1,1,1,0]`. These forbid `a && b` and `b && c`. The exact count is **5**. Conditioning `b=true` gives **1**; conditioning `b=false` gives **4**. These are separate `Problem` values, so the condition is part of each certificate's source.

Core entry points: `FiniteConstraintCounter.count` and `FiniteConstraintVerifier.verify`. SDK entry points: `FiniteConstraintSdk.count` and `FiniteConstraintSdk.verify`. Pass an explicit `OptimizationBudget` appropriate to the retained tables; the SDK introduces no second arithmetic implementation.

The [complete Java example](../../examples/external-consumers/checked-math-java25/src/main/java/example/FiniteConstraintCount.java) prints counts `5`, `1`, `4`. Build and run it through the [standalone Maven consumer](../../examples/external-consumers/checked-math-java25/README.md); `mvn -f examples/external-consumers/checked-math-java25/pom.xml clean verify` runs all six examples after the source SDK has been installed. The published 0.5.3 SDK does not contain this API.

## Evidence

`FiniteConstraintCounterTest` checks empty instances, isolated variables, weighted tables, contradictions, conditioning, forged local tables/totals/sources/witnesses and resource boundaries. Seventy seeded arbitrary small problems are compared with independent complete enumeration. `FiniteConstraintSdkTest` checks the public facade and cancellation. These tests check exactness and boundary behavior, not an unmeasured performance improvement.

## Learning a plan choice

`FiniteConstraintPlanLearning.train` compares the three strategies on earlier named problems.
The model stores measurements and graph characteristics of training inputs. At application it uses
the conditioned input structure to choose a strategy; the chosen plan must pass the ordinary
count verifier. The SDK exposes training and application alongside the three fixed controls.
See the [public-project study with input hashes, budgets, failed cases and external counts](../../research/benchmarks/constraint-planning-transfer/README.md).
Acquisition work is charged once per trained model, followed by selection, planning, solving and
certificate replay for each subsequent input.

## Research relationship

The motivation is the local-constraint/counting transfer identified in the [quantitative reduction manuscript](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/The-Exponential-Time-Hypothesis-is-False-October-25-2026/build/quantitative-reduction.tex). The implemented component is a conventional, finite, exact factor-elimination engine with an independent checker. Its correctness does not rely on the manuscript's headline complexity claim.

## Indexed elimination and signed CNF, v1 (2026-11-25)

`FiniteConstraintSdk.countIndexed(problem, strategy, budget, cancellation)` accepts the same weighted factors
and order as `count`. Each live nonscalar factor belongs to the bucket of its earliest remaining variable.
Eliminating that variable consumes its bucket and indexes the resulting factor. Appending factors in ID order
preserves the original certificate's ordered input IDs. Independent replay still checks every active factor
for bucket membership. Existing counter and training entry points retain the historical construction profile.

`FiniteConstraintCnf.Source` takes the declared number of variables, a list of clauses of signed one-based
DIMACS literals, and fixed assignments keyed by **zero-based** variable ID. For example,
`new FiniteConstraintCnf.Source(4, List.of(List.of(1, 2, 3, 4)), Map.of(0, false))` has seven models.
The empty clause is false and the empty conjunction is true. Every unfixed declared variable contributes to the count,
including those absent from the clauses.

`FiniteConstraintSdk.lowerCnf` returns a verified factor problem and `verifyCnfLowering` checks the translation.
Each clause longer than three literals gets fresh private variables. A gate enforces that the new variable
equals the OR of the running prefix and the next literal. Its two directions determine a unique prefix value
for every original assignment. The final factor requires the remaining disjunction to hold. A clause of length
`k` introduces `max(0,k-3)` auxiliaries and one additional factor for final acceptance. All scopes have
at most three distinct variables, including when literals repeat or have opposite signs.

`FiniteConstraintSdk.countCnf` checks the translation and calls the indexed min-degree counter under the
remaining budget. Its certificate holds the original CNF and the elimination certificate, whose source
is the lowered factor problem. Witness keys below the original variable count give the original assignment.
`verifyCnf` binds clause order, literal order, declared variables and fixed values to the supplied source.
It checks every truth-table row of each required prefix and acceptance relation, then independently replays
elimination. `UNAVAILABLE` includes the stopping reason and paid work. General CNFs can still exceed induced
width, memory, time or work limits.

### Measured work

`FiniteConstraintCnfTest` compares a 100-variable weighted chain with 50 disjoint pairs on the same
100 variables. Both fix variable 0 to true and use the ordered two-variable weight table `[2,1,0,1]`
on every edge. Each comparison holds source, order and replay constant and checks certificate equality.
Counts below are charged work units for construction, ordering and verification.

| Graph | Order | Historical scan | Indexed v1 |
| --- | --- | ---: | ---: |
| Chain | Ascending | 53,613 | 39,751 |
| Chain | Minimum degree | 61,741 | 47,977 |
| Chain | Minimum fill | 62,134 | 48,370 |
| Disjoint pairs | Ascending | 32,456 | 23,151 |
| Disjoint pairs | Minimum degree | 38,184 | 28,977 |
| Disjoint pairs | Minimum fill | 38,430 | 29,223 |

A single positive 62-literal disjunction produced `2^62-1` with 121 lowered variables and 60 factors,
using 68,714 work units through verified return. Additional controls compare 100 seeded small CNFs
against complete enumeration, count extensions separately for every original assignment of a repeated-literal
example, and reject an independently replayable factor proof with a forged gate row.
Run `mvn -pl regelsuche-optimization-sdk -am -Dtest='FiniteConstraint*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
with Java 25. JUnit report entries give the work measurements for each graph and the wide clause.

### Workbench

Open **Exakte Rechennachweise → Belegungen einer CNF zählen**. Enter one clause per line. Signed
one-based literal numbers encode the variable and its polarity. A single `0` line represents an empty clause,
and a blank formula represents the empty conjunction. For three variables, the lines `-1 -2` and `-2 -3`
give five assignments. Fixing variable 2 to true (enter `2`) gives one. The form keeps edited input in this
tab's session storage and clears any previous result after an edit.

The web adapter accepts up to 256 variables, 256 clauses, 4,096 total clause literals and 32 KiB of JSON.
A request runs with its selected work limit (at most 2,000,000), 40,000 cells and a two-second SDK timeout.
The cancel button addresses that live request's SDK cancellation token. An exact result offers the complete
source and elimination certificate as a JSON download, with arbitrary-size weights and the count encoded as
decimal strings. A resource or cancellation result displays the reason and work with no certificate.
