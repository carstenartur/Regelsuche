# What retaining learned tactics improved

Sharing native node counts increased on-time completions on all five streams. On the mixed Horner/dot-product stream the primitive solver completed **861 instead of 642** within 524,288 logical units. The previous best seven-schema configuration completed 720. The proof-learning change also made a measurable difference during training. Bounding the cost of a validation attempt by the price of an already observed primitive proof bought the **same 14 checked schemata for 41.8% less acquisition work**.

The new learned solver completed **379 and 480** cases on the two held-out families. No learned rule was proposed by its admission gate on either family. Disabling all acquired proposals left every proposed solution and the total work unchanged. The strongest registered controls completed **384 and 480**. The [protocol](protocol.md) required 10% more verified solutions in each family and an individually necessary learned tactic in each. Both families failed the primary test.

## Prospective results

Each family contains 512 cases. C comprises products of split-and-recombined stage gains. D comprises planar determinants with differently calibrated coordinate contributions. Each of the ten arms pays its own cold setup and has the same family deadline of 524,288 logical units. A task also has its own cap. “Local” counts all successful tasks under their individual caps after running the full stream. “Cold” additionally enforces the family clock. These are logical-work measurements.

| Registered arm | C cold | C local | C full work | D cold | D local | D full work |
|---|---:|---:|---:|---:|---:|---:|
| Primitive search | 352 | 352 | 355,648 | 448 | 448 | 218,355 |
| Previous automatic search | 352 | 352 | 367,282 | 448 | 448 | 232,075 |
| Previous seven schemata, shared checker | 352 | 352 | 368,004 | 384 | 384 | 333,538 |
| Previous six schemata, learned selection | 260 | 320 | 601,337 | 331 | 352 | 545,808 |
| Recent library only | 384 | 384 | 405,121 | 480 | 480 | 295,907 |
| Historical library only | 384 | 384 | 355,693 | 480 | 480 | 246,447 |
| Both previous libraries | 384 | 384 | 427,664 | 480 | 480 | 318,450 |
| All 26 schemata, forced matcher | 292 | 320 | 557,767 | 284 | 320 | 566,834 |
| All 26 schemata, learned admission | 379 | 384 | 529,847 | 480 | 480 | 420,633 |
| Same admission, all proposals disabled | 379 | 384 | 529,847 | 480 | 480 | 420,633 |

On C the required count was **423**. Five locally solved cases missed the cumulative deadline. On D the leading controls solved 480, so 10% would have required 528 among 512 cases. No case in either family qualified for a single-rule knockout. Forced matching did use learned rules on the new inputs, with 1,056 learned steps on C and 219 on D across all returned paths. It completed only 292 and 284 cases by the cold deadline. Seeing a familiar local identity and applying it unconditionally were insufficient for a net improvement.

One example is C/3. The output of `((a+70004)*(e-j)+(a+70004)*j)*(b*(f-k)+b*k)` reduced to `(a+70004)*e*(b*f)`. Primitive search with the original accounting needed 275 units against an individual cap of 256. The retained candidate finished the same four primitive steps for 224, and its matched ablation did likewise. The achieved source reduction is real. The within-cap improvement in this case comes from cheaper primitive evaluation. [Full example rows](results/examples.json) · [Independent reconciliation](results/verification.json)

## Which gains survive removing learning?

The [additional technical reference](development/primitive-reference.md) runs both primitive solvers without any training, routing or matcher setup. Native node counts are shared between state evaluation and goal checking within a query. Each first count, cache hit and eviction is charged. The chosen proof still goes through final replay and the exact polynomial audit. On the final families each ordinary primitive row was matched against the official BASE ledger.

| Stream | Primitive by deadline | Shared counts by deadline | Primitive full work | Shared counts full work |
|---|---:|---:|---:|---:|
| Horner/dot product (development) | 642 | 861 | 834,410 | 622,234 |
| Constant interpolation (development) | 352 | 384 | 404,800 | 339,904 |
| Squared radii (development) | 320 | 384 | 379,744 | 308,384 |
| Stage gains (held out) | 352 | 384 | 355,648 | 291,488 |
| Planar determinants (held out) | 448 | 480 | 218,355 | 183,362 |

On the development interpolation and squared-radius streams, the full candidate completed 384 and 352. Disabling its learned proposals while retaining its paid admission, matcher and preparation reduced both to 192. Its returned paths used 448 and 832 learned steps. Those are genuine benefits inside the configured matcher. The independent primitive reference completed 384 and 384 on the same tasks. The previous learned selector had completed 256 and 192. On the public mixed stream the new full candidate completed 447 compared with 376 for its matched ablation, 720 for the previous seven-schema solver, and 861 for the primitive solver with shared counts. The new candidate paid 232,215 setup units. [Paired development outcomes](development/full-matcher.json) · [Standalone measurements and ledger hashes](results/standalone.json)

The learned gate uses the overall objective, search-depth cap and the outer operators of the input. It does not inspect a learned occurrence inside an unfamiliar surrounding tree before declining the matcher. A subsequent experiment could learn when an inexpensive local applicability check pays for itself. It would need new inputs and the standalone primitive solver as a direct baseline.

## From native proofs to a usable rule library

The public APIs operate on typed tasks and verified models. `CheckedLearnedSchemaModel.retainWith` keeps older proofs, merges shared schema IDs and evidence, returns the work of composition, and exposes any omitted IDs. Both input acquisitions and loads must be charged. `ProofDepthChallenge.from` turns an actual solved multistep input into a separate validation task whose proof must fit one fewer search level within the observed price. `CostAwareTacticRouter.fit` compares actual primitive and learned searches on calibration inputs. It meters failures, fitting, dispatch, both proof checks and the selected lane. The retained model and the chosen solver are immutable during evaluation. `solveWithSharedNodeCounts` is also available directly on a primitive solver.

```java
var primitive = TypedLearnedMoveInventory.primitives(genome).newTaskSolver();
var primitiveAnswer = primitive.solveWithSharedNodeCounts(task);

var retained = historical.retainWith(recent);
var fit = CostAwareTacticRouter.fit(genome, retained.model(), calibrationTasks);
var solver = CostAwareTacticRouter.prepare(genome, retained.model(), fit.admission(), Set.of());
var answer = solver.solve(task);
```

The previous seven- and six-schema libraries overlap on one ID. The new acquisition used the first three inputs from each previous family as lessons and the next three as depth-challenge gates, after solving each reference anew. Fourteen checked schemata were acquired. All 26 distinct schemata fit the combined library, including all previous IDs. The paired acquisition experiment returned byte-identical models at a total cost of 75,408 versus 129,515 units. Bounding gate work by the actual reference prices saved 54,107 units (41.8%). Acquisition here includes both sets of lessons, reference and gate attempts, source reads, formation and composition within the new acquisition. The full candidate also pays historical costs, calibration and preparation. [Receipts for gate prices](development/proof-depth-prices.json) · [Training proofs and charges](training/trace-acquisition.json)

Calibration used 12 prior public cases, eight other earlier interpolation/squared-radius inputs and four earlier mixed-stream inputs. After the public development comparison the registered selection rule chose the full matcher. The opt-in thin matcher activated nine schemata and paid to retest the restricted selection. It had 439 cold completions on the mixed stream. The full matcher had 447, and coverage on the two other development streams was unchanged. [Selection rule and both runs](development/selection.md)

Only the full input families, their permutations and their polynomials were held out. The final generators intentionally combine previously known local algebraic relationships and include misses. The separate Python code checks input generation, disjointness under variable renaming, exact polynomial equivalence, goal sizes, named proof steps, cumulative clocks and removal outcomes. The measured search-operation counters use the Java implementation's accounting. These are synthetic within-family benchmarks. Counts and logical work do not establish elapsed-time speedups on external programs.

## Independent execution

The [registered protocol](https://github.com/carstenartur/Regelsuche/commit/064d2d3e6730bd6e5fa68622cdbf25fb0ec0d553) and the [complete freeze](https://github.com/carstenartur/Regelsuche/commit/27bb13a8c861578912ef968ab73b2d06cb95fce7) preceded all final inputs. A supplementary unit test initially assumed an ordinary primitive provider declared exhaustive enumeration. That provider reports an incomplete relation. The [test correction](https://github.com/carstenartur/Regelsuche/commit/e81637e2a147b6a57969385d7f704b076e3b0a98) compares against the ordinary primitive outcome and independently checks a genuinely empty provider relation. This single regression-fixture change preceded final generation. The solver, inputs, selection, budgets, evaluator and original seed were unchanged.

Two fresh Java 25 runs in opposite arm orders agreed on all **10,240** official task/solver records, including failed cases, and on **3,090** distinct within-family proof paths. Independently charged development comparisons and the standalone primitive study also reproduced. All eight Maven projects succeeded with 4,678 ordinary tests passed and 13 opt-in tests skipped. The research jobs ran separately. The [complete hosted run](https://github.com/carstenartur/Regelsuche/actions/runs/46155799785) retains both rotations. The [compressed mathematical ledgers](results/) retain their common content and both sets of timings. [Standalone solver and reproduction](shared-node-count-control.md)

## Reproducing the measurements

Use a JDK 25 checkout, Maven, Python 3, and the repository root. The study files on the complete freeze include all five deterministic training receipts. Two separate Maven invocations produce fresh JVMs. Only the mathematical ledgers are expected to be byte-identical.

```bash
set -eu
seed=27bb13a8c861578912ef968ab73b2d06cb95fce7
study="$PWD/docs/research/prospective-retained-tactics"
work=$(mktemp -d)
for r in 0 1; do
  mvn -B -ntp -pl regelsuche-learning -am -Dtest=RetainedTacticStudyTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dretained.phase=train \
    -Dretained.research="$PWD/docs/research" -Dretained.output="$work/train-$r" test
  diff -rq "$study/training" "$work/train-$r"
  mvn -B -ntp -pl regelsuche-learning -am -Dtest=RetainedTacticStudyTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dretained.phase=final -Dretained.freeze="$seed" \
    -Dretained.research="$PWD/docs/research" -Dretained.rotation="$r" \
    -Dretained.output="$work/final-$r" test
  mvn -B -ntp -pl regelsuche-learning -am -Dtest=RetainedTacticDevelopmentTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dretained.research="$PWD/docs/research" \
    -Dretained.rotation="$r" -Dretained.development.output="$work/dev-$r" test
done
python3 "$study/verify.py" "$work/final-0" "$work/final-1" "$seed" > "$work/verification.json"
python3 "$study/development/summarize.py" "$work/dev-0" "$work/dev-1" \
  --training "$work/train-0" > "$work/development.json"
```
