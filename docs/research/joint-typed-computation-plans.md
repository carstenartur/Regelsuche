# Joint typed computation plans

`JointComputationPlan` is an immutable set of typed inputs, optional named
definitions, and explicitly named, typed outputs. Expressions are existing
`Expr`/`FunctionExpr` values. Definition references resolve before search; missing
references, cyclic definitions, shadowed inputs, duplicate output names, operator
arity/type mismatches, and output type mismatches are rejected. Structural depth
and visit limits apply. The search envelope preserves output names and order.

`PreparedJointComputation` interns equal structural expressions across all
outputs into one DAG, validates operator signatures, and retains a topological
schedule. Interning uses identity memoization plus nonrecursive keys containing
the operator/leaf and integer child IDs. It never hashes an `Expr` recursively.
Each node is evaluated once per execution. Values are local to that
execution, and intermediate slots are released after their last use; output
nodes remain live through return. Alias outputs reference the same computed
value. `ComputationBackend` supplies arbitrary typed operator semantics and
per-execution input validation. It need not generate Java. Backend semantics must
remain stable, and pure operations must not mutate their inputs.

The cost receipt includes unique operation work, peak live storage, retained
output storage, output-binding count, operation count, and inspection work.
Storage is the backend's declared units for the retained schedule, not measured
heap bytes. It describes evaluation slots, excluding caller-owned input storage
and the immutable AST. The default objective weights operation work, peak live
storage, retained output storage, and output bindings equally. Weights are
explicitly configurable. Preparation inspections are charged separately from
these predicted execution costs, including definition resolution, structural-key
field/edge work, and schedule/liveness scans. Resolution also memoizes identities
and tracks height and a saturated expanded-tree size. A compact DAG can be
prepared directly; `searchExpression()` rejects it before serialization if its
tree expansion exceeds the existing frontier transport's node/depth limits.

`JointPlanSearch` supplies this cost as a charged `StateValue.Assessment` to
`TypedMoveSearch`, which delegates to the existing `MoveSearch` frontier. It also
uses the cost for `TypedSourceOnlySearch` incumbent selection. Consequently
sharing changes priority **during** exploration, before final preparation.
There is no target expression and no additional search engine. Existing state,
depth, work, and candidate budgets bound the search. Candidate generation,
semantic checking, initial/final preparation, incumbent inspection, and selected
path replay have logical work receipts. Atomic overruns remain visible through
`Result.withinBudget()`. These counters are mechanical units, not total CPU time.

The domain callback proposes expressions and independently proves equivalence.
Each admitted candidate must preserve input/output bindings, pass domain
checking, and prepare with matching types. Final preparation checks the selected
outputs against the original source again. `prepareVerified` provides the same
boundary for an externally supplied candidate. A proof label or lower score is
not authority.

## Modular example and boundaries

`ModularComputationDomain` lives in `regelsuche-math-algorithms`, without a
dependency on search. `ModularJointPlans` in experiments only wires that domain
to the general search/backend interfaces. The reusable optimizer and scheduler
are in search.

The domain supports integer affine exponents with a sufficient nonnegativity
proof: integer nonnegative coefficients over explicitly nonnegative or positive
inputs. It reuses the existing exact `Polynomial` arithmetic. Every modular
operation requires a provably positive modulus. Replacing `modpow(a,1,N)` with
bare `a` additionally requires `0 <= a < N`. Input-dependent conditions are
checked for **each execution**, even if the plan's output becomes a bare input;
modpow/modmul also check local runtime premises. Negative and fractional
exponents and unsupported nonlinear exponent proofs are rejected.

The generic candidate law considers powers already present with a common base
and modulus. Given exponents `E` and `F`, it proposes
`modmul(modpow(a,F,N), modpow(a,E-F,N), N)` when `E-F` has a nonzero nonnegative
affine proof. Unit powers can then simplify under the normalization premise.
It receives no desired result, variable-name convention, or specially supplied
exponent. The independent checker compares modular exponent normal forms and
does not regenerate candidates or trust their rule names.

For the development input with outputs `a^(x+1)` and `a^(2*x+1)` modulo `N`, the
existing source-only frontier reaches a DAG containing one `modpow(a,x,N)` and
two modular products. The integration test verifies repeated BigInteger results
at several exponents and includes renamed inputs with different affine offsets.
This is a public development/regression case, not independent transfer evidence
or a proof of global optimality. The example backend's static operation costs
are declared estimates (symbolic modpow 1000, modmul 10, small literal powers
priced by bit length), not measured speedups. Unsupported modular expressions
and more general polynomial/nonnegative reasoning remain outside this domain.

Targeted verification covers sharing, mixed types, liveness, binding resolution,
cycles, tampering, missing premises, runtime domain failures, visible budget
overruns, and actual frontier reachability. Frozen experiment assets are not
modified by this implementation.

## Paid architecture/execution diagnostic, 2026-09-20, before DAG-bound fix

This is not a learning comparison. `ModularJointPlanExperiment` compares the
prepared DAG with direct BigInteger formulas, including the same runtime input
premises and identical independently recomputed result audits. It actually runs
sequences of 1, 10, 100, and 1000 different inputs, with three retained trials
and alternating route order per fresh JVM. Exponents have 256 bits and moduli
have 1024 bits. There is no warmup or discarded trial. Each scenario charges the
complete one-time search, checking, and setup cost, as well as input generation,
execution, and audit. Setup is physically performed once per report, not once
per input or re-extrapolated from one execution.

Two fresh-process reports and their external process measurements are retained
under `joint-modular-plan-evidence/2026-09-20/`. The runtime inventory hashes the
exact copied class files and pinned Jackson dependencies. The reports identify
the dirty worktree rather than claiming a clean committed authority. The
temporary executable runtime snapshot remains under the build report directory.
No frozen benchmark resource is copied or executed by the runner.

Both runs explored four states and selected three generic rewrites. Predicted
cost changed from 2014 to 1028; measured logical search/selection/replay work was
1450 units, with a further 223 initial/final checking and preparation units.
These logical units do not substitute for the measured wall/CPU/allocation data.

The following ratios are direct/prepared median wall time over the three
retained trials; values below one favor direct evaluation. These small samples
are diagnostics, without a confidence interval or performance acceptance claim.

| Inputs | Run 1 execution | Run 1 fully paid | Run 2 execution | Run 2 fully paid |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 2.841 | 0.055 | 1.671 | 0.039 |
| 10 | 1.199 | 0.058 | 1.441 | 0.064 |
| 100 | 1.318 | 0.142 | 1.757 | 0.153 |
| 1000 | 2.278 | 0.566 | 1.647 | 0.582 |

For 1000 inputs, fully paid direct/prepared median wall times were
618.802/1094.003 ms and 636.107/1092.150 ms. The cold search/check/setup stage cost
656.694 ms and 625.123 ms and allocated about 23.7 MB on the current Java thread.
Prepared execution alone allocated about 10.8 MB per 1000 inputs versus direct
execution's 12.9 MB. Including setup and audit, prepared allocation was about
67.0 MB versus direct's 45.5 MB. Consequently there was **no fully paid gain** at
any tested length, and no threefold end-to-end result. The paid cold stage is the
observed bottleneck; this diagnostic does not isolate its class-loading, JIT,
serialization, and proof contributions.

Process CPU measurements include JIT/GC threads. Allocation measurements cover
the current Java thread. Heap readings are absolute before/after samples, not
per-route peaks. External Linux `wait4` accounting includes JVM startup and
report serialization and reported 172424/174332 KiB peak RSS for the complete
processes. All raw trial values, matching output checksums, and commands remain
in the JSON artifacts.

Reproduction from a built checkout (Java 25, existing offline dependencies):

```sh
JAVA_HOME=/path/to/jdk-25 PATH=/path/to/jdk-25/bin:$PATH ./gradlew \
  :regelsuche-search:test --tests '*JointComputationPlanTest' \
  --tests '*JointComputationDagBoundsTest' \
  :regelsuche-math-algorithms:test --tests '*ModularComputationDomainTest' \
  :regelsuche-experiments:test --tests '*ModularJointPlansTest' \
  --tests '*ModularJointPlanExperimentTest' --offline --max-workers=1 --console=plain
python3 scripts/run-joint-modular-plan-experiment.py \
  build/reports/joint-modular-plan-new-run --java /path/to/jdk-25/bin/java --runs 2
```

The executed measurement command used
`/workspace/scratch/fcfe4cbdbc35/runtime/jdk-25.0.2+10/bin/java` and output
`build/reports/joint-modular-plan-v1-20260920-a`. The runner refuses an existing
output directory and retains each run's raw report, log, process accounting,
and exact runtime-file inventory. The original thirteen targeted tests passed
together. A subsequent independent review found and corrected the compact-DAG
hashing issue described below. The two original measurement reports and runtime
inventory were retained unchanged; their logical-work totals and timings
describe the earlier implementation.

## Separate DAG-bound regression correction

The earlier interning table used `HashMap<Expr, ...>`. For definitions
`t0 = x; ti = add(t(i-1), t(i-1))`, recursive record hashing could perform
exponential work before the explicit visit bound was reached. A separate process
against the retained old bytecodes took about 656 ms to prepare depth 24 and
was killed at a three-second deadline for depth 48. The old resolver also
revisited a directly shared AST as a tree until exhausting its visit limit.

The correction uses the identity memo and topological keys above, charges the
key/schedule work, and rejects oversized tree transport before the codec runs.
Two independently built equivalent depth-48 DAGs now share 48 operations and one
retained output value, with 2189 charged inspections. The separate isolated
depth-48 preparation probe completed in about 43 ms, including cold preparation
class initialization. These probe timings establish a bounded regression fix;
they are not replacements for the unchanged execution study. Reproduction
records and fixed-source hashes are in `dag-bound-fix.json`.

Four additional regression tests cover a killable named-DAG subprocess,
identity-shared raw ASTs including `withExpression`, early frontier-expansion
rejection, and structural depth rejection. A clean rebuild was needed after an
incremental build left stale existing class files beside newly emitted nested
classes. The final combined seventeen targeted tests passed together in 37 s.
