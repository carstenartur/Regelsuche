# Prospective mixed task-knowledge protocol v2

Revision: `regelsuche.knowledge-mixed-workload/v2`. Fixed on 2026-11-10, before
running its timing experiment. This is a public, within-family engineering
experiment, not a sealed holdout, external novelty result or marketplace claim.

## Defect and correction

The frozen `knowledge-lifecycle-improvement-v1.json` supplied one depth-3
composition with offset 17 to the performance-promotion gate. Its subsequent
stream contained shallow, medium, deep, compound-sum, compound-product, offset,
negative and already-small tasks. A saving on that one promotion task therefore
estimated the wrong query distribution. The existing `KnowledgeCostGate`
correctly required both wall and CPU payback and vetoed each quality regression;
it could not compensate for missing cases.

The preferred new Java entry point is `MixedTaskKnowledgeStudy`. Its immutable
`MixedTaskKnowledgeWorkload` supplies all eight roles in exactly the same order
and with equal weights in promotion and evaluation. Promotion has three paired
measurement repeats, with order alternating inside each batch. One specialised
win cannot hide a net cost increase across the other seven roles. A single
quality regression remains an unconditional veto regardless of savings.

Legacy worker requests and all frozen v1 input/result files retain their meaning.
New worker requests opt into the named v2 workload and cannot simultaneously
supply a specialist override. The new study selects v2 by construction.

## Inputs and separation

Let `N(x,0)=x` and `N(x,d)=(N(x,d-1)+b_d)*(N(x,d-1)-b_d)+b_d*b_d`.
The exact strings, node limits and order are emitted in every study report.

| Role | Promotion | Evaluation | Maximum nodes |
| --- | --- | --- | ---: |
| shallow | N(r,1)+29 | N(r,1)+53 | 7 |
| medium | N(r,2)+31 | N(r,2)+59 | 9 |
| deep | N(r,3)+37 | N(r,3)+61 | 11 |
| compound-sum | N(r+s,1)+41 | N(r+s,1)+67 | 7 |
| compound-product | N(r*s,2)+43 | N(r*s,2)+71 | 9 |
| offset | N(z,2)+47 | N(z,2)+73 | 9 |
| negative | (r+s)*(r-s)+s*(s+2) | (r+s)*(r-s)+s*(s+3) | 1 |
| already-solved | 2 | 3 | 1 |

All seed lessons and validation tasks, both improvement batches including the
no-op attempt, promotion inputs and evaluation inputs are checked using exact
polynomial alpha identities. The study fails before training if any of the
training/promotion, training/evaluation, or promotion/evaluation intersections
is nonempty. Changing variable names does not pass this check. Constants and
coefficients distinguish the split polynomials, not renaming. Tests explicitly
try a renamed promotion polynomial and require rejection.

This separation avoids exact/alpha-polynomial reuse. It does **not** establish
independent mathematical families: the positive cases intentionally share a
composition law. Offset choices also change some source shapes relative to v1;
v2 does not claim identical v1 instances or an empirical deployment distribution.
Eight-role equal weighting is a declared workload, not evidence about user traffic.

Seed acquisition uses depths 1 and 2; the first improvement attempt uses depth 3;
the second is the predeclared no-op `x+13`, `y+17` with validation `z+19`.
Validation contains compound and offset tasks plus negative/already-small controls.
Inputs are never selected from observed timing. Rejected seeds and attempts are
reported, not removed to force a favourable result.

## Cost and quality accounting

Each arm starts from empty knowledge, pays both seed acquisitions, writes and
strictly reloads its snapshot, uses the bounded prepared session, and executes the
entire evaluation stream. The improvement arm additionally pays every attempted
batch, split check, candidate formation, validation, paired promotion trial,
decision and snapshot update. The default `IMPROVEMENT_RUN` adoption gate
requires a candidate to repay cumulative elapsed cost since the improvement run
began through the final precommit measurement, including earlier rejected/no-op
attempts and intervening controller work. The remaining final-gate/controller
overhead stays in `Result.paid` and the end-to-end study total.
`Attempt.paid` remains the incremental receipt; `Attempt.adoptionCost` states the
actual cumulative cost used for its promotion eligibility. This is conservative
when a prior candidate was already accepted: the next candidate's marginal gain
must again cover the whole improvement run. Seed acquisition is shared sunk cost
for that gate; it is included once in each end-to-end arm, never double charged.
Explicit `LEGACY_ATTEMPT` scope preserves historical v1 worker decisions. New
named-workload worker calls and the preferred study use `IMPROVEMENT_RUN`. All attempts, including no-ops/rejections, are in
its receipts and total elapsed wall/process CPU. The future horizon is exactly
`blocks * 8`, never an inflated payback horizon.

Every evaluation output is independently checked by exact polynomial analysis,
including each witness edge, source/endpoint binding and a separate occurrence
node count. Its audit operation units and time remain paid. API work is reported
alongside audit work; it is not treated as an estimate of bit complexity or time.
I/O, parsing, controller work, allocations, GC, JIT and snapshot serialization
are included in measured arm time even when not represented in mechanical units.
Unavailable CPU or incomplete accounting prevents a successful performance claim.
A caught failure keeps the elapsed arm cost, completed query receipts and an
explicit incomplete status; known mechanical work is then only a lower bound.

Use the complete arm's `paidIncludingSetupAndAudit`, not query time alone, for
amortisation over the declared horizon. Compare paired repetitions only when both
arms are accounting-complete and every query is verified, and check solved status
and output score per role before considering costs. The emitted `comparisons`
do this per repetition and report the observed wall/CPU savings and whether both
resource totals repaid the measured overhead; these observations are not a
statistical confidence claim. Report both wall and CPU;
a faster query-only median is insufficient to establish end-to-end repayment.
`setupCost` and individual query costs are explanatory breakdowns, not extra costs
to add to the enclosing total. `improvement.paid` likewise is already included.

Arms run sequentially in one JVM and their order alternates by repetition. This
is a warm shared-runtime study, not the historical fresh-producer/consumer
process experiment; JIT/order effects remain a limitation. The outer process
measurement includes startup/shutdown and final report serialization. Internal
`studyPaidBeforeSerialization` includes common split checking and orchestration
but explicitly excludes final JSON serialization; do not present it as the full
process cost. No independent correctness audit is removed to obtain a saving.

## Reproduction (official dependencies)

From the repository root, with Java 25 and Maven 3.9.x:

```sh
mvn -pl regelsuche-learning -am -Dtest=MixedTaskKnowledgeWorkloadTest,KnowledgeCostGateTest,TaskKnowledgeLifecycleWorkerTest,TaskKnowledgeSelfImproverTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl regelsuche-learning -am -DskipTests install
mvn -pl regelsuche-learning dependency:build-classpath -Dmdep.outputFile=target/mixed-v2.classpath
```

Then run with a new, nonexistent snapshot directory (the output directory for the
JSON and process receipt must already exist):

```sh
/usr/bin/time -v -o mixed-v2-process.txt java -Xmx512m \
  -cp "regelsuche-learning/target/classes:$(cat regelsuche-learning/target/mixed-v2.classpath)" \
  de.regelsuche.evolution.MixedTaskKnowledgeStudy mixed-v2-snapshots \
  > mixed-v2-report.json
```

Optional positional arguments after the directory are blocks (1–128, default 16)
and repetitions (1–9, default 3). Record the commit, Java version, exact classpath
and dependency hashes with the report. A smaller smoke run does not replace the
registered default when making performance claims. Official Maven qualification
remains required even if a supplemental source compilation succeeds.

## Supplemental source/resource reproduction

When canonical Maven dependencies cannot be resolved, this explicitly supplemental
check uses an already available bundled SDK only for external libraries:

```sh
regelsuche-learning/scripts/run-mixed-v2-supplemental.sh \
  /absolute/path/to/jdk25 /absolute/path/to/regelsuche-optimization-sdk.jar \
  /absolute/path/to/new-supplemental-output
```

The script compiles all referenced project classes from the current checkout,
explicitly includes every provider named by the current search/learning service
descriptors, and supplies both resource directories at runtime. It removes all
bundled project classes and project service metadata before compiling; old SDK
project semantics cannot silently substitute for current code. It first executes
the actual-adoption and mixed-workload contracts, then runs the default study.
Its raw output, process receipt, provider descriptor contents/hashes, class
inventory and source-tree hashes provide the reproduction record. The existing
SDK's third-party versions remain a supplemental dependency closure, so this does
not replace official Maven/CI qualification.

Runtime resources are essential: `-Xprefer:source` alone does not discover
`ServiceLoader` providers or install their service descriptors. An earlier run
omitting learning resources is retained under `initial-incomplete-provider-attempt`
and is not qualifying learning evidence. Corrected measurements and conclusions
are in `results.md` and `corrected-current-providers/`.

## Remaining representation-audit proof obligation

`LinearRepresentationPlanner.audit` currently solves the source again using an
alternate full representation (matrix for scalar/block results; scalar for matrix
results) and compares complete `ExactLinearSolutionConsequence` values.
`RepresentationStrategyLearner` includes that audit cost both in learning and
application. It is expensive, but it checks the full solution classification,
particular solution and nullspace basis, not just a residual.

A cheaper exact verifier must independently bind a certificate to every original
source equation and variable order, establish exact coefficient extraction,
check `A*p=b` and `A*N=0`, and prove **completeness** of the returned affine solution
set. The last obligation requires both independence of the claimed nullspace
basis and `rank(A)=n-dim(N)` (for example, an exactly verified nonsingular rank
minor plus a matching upper-rank certificate, or invertible row-operation replay
into a checked echelon form). Residuals alone accept an empty or incomplete basis
for an underdetermined system. Unique claims require full column rank.
Inconsistency requires a source-bound left-null witness `y*A=0` with `y*b != 0`;
a Boolean assertion or arbitrary nonzero contradiction marker is insufficient.
Block composition additionally requires exhaustive, nonoverlapping equation and
variable coverage, including constant/contradictory rows and all free parameters.
A verifier must account for certificate parsing, exact arithmetic, rank checks,
source extraction and rejected certificates within its stated budget and preserve
canonical parameter/rank semantics if equality of consequence objects is retained.

No smaller verifier satisfying all these obligations is implemented here. The
full independent audit remains enabled and paid. A residual-only shortcut would
weaken the mathematical contract and is deliberately excluded.
