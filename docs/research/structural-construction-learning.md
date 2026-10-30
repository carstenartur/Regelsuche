# Structural construction and checked task learning

Source baseline: `459293be659a4adf3fd30f5e9576c934ea00ca21` (30 October 2026).
This is an opt-in extension of the existing task solver and checked-schema learner,
not a replacement search kernel, CAS, or a set of hard-coded historical answers.

## Mathematical construction API

`AlgebraicConstruction` offers typed finite scalar operations. Given a source `E`
and a proposed auxiliary `T`, it can construct:

| Operation | Meaning |
| --- | --- |
| `ADDITIVE_COMPLEMENT` | `(E + T) - T` |
| `ADDITIVE_SPLIT` | `T + R`, computing the exact polynomial remainder `R = E - T` |
| `MULTIPLICATIVE_COMPLEMENT` | `(E * T) / T`, subject to the original domain |
| `MULTIPLICATIVE_SPLIT` | `T * (E / T)`, subject to the original domain |
| `EXACT_FACTOR_SPLIT` | `T * Q`, with a computed exact monomial quotient and no new denominator |
| `FACTOR_TRANSFER` | `(A*T) * (B/T)` for an input `A*B`, subject to the original domain |

For example, `7*x` split by `3*x` leaves `4*x`; `12*x^4` split by the
exact factor `3*x^2` leaves `4*x^2`. The latter identity remains valid at `x=0`.
In contrast, an inserted symbolic divisor requires a carried nonzero premise.
A known nonzero rational constant needs no extra premise. Zero, including `t-t`,
is rejected even when a caller supplies the contradictory premise `t-t != 0`.
The existing exact polynomial and rational-function checkers remain authoritative.
Large integers are kept exact; no binary-floating-point path is introduced.

```java
var request = new AlgebraicConstruction.Request(
    AlgebraicConstruction.Operation.EXACT_FACTOR_SPLIT,
    "12*x^4", "3*x^2", List.of(), 100_000);
var result = new AlgebraicConstruction().construct(request);
```

`explore(...)` proposes a bounded set of source subexpressions and cross-products
without a target expression. It reports every attempt and its logical work.
The procedure does not invent an unrestricted free variable on the right of a
rule. Existing unbound-placeholder rejection is unchanged. Source and auxiliary
polynomials are limited to 32 terms, total degree 12 and four variables. The
existing exact-parser/coefficient limits also apply. This API is rational-scalar,
not a license to reorder matrices or introduce roots, branches or logarithms.

The work receipt includes observed polynomial work, bounded syntax processing
and an explicitly conservative charge for the rational audit. It is not a full
JVM/heap/bit-operation measurement. Atomic bounded overruns retain their charge
and emit no successful candidate.

## Shared task execution

`TaskConstructionProfile.STRUCTURAL_V1` attaches existing square exposure,
square completion, difference-of-squares factoring, a bounded exact-factor
partition, and existing residual balancing to the same `TypedSourceOnlySearch`
frontier used by the cumulative learner. Both the baseline and learned arms
receive the same construction profile. `NONE` remains the default.

```java
var knowledge = CumulativeTaskKnowledge.empty(
    genome, TaskConstructionProfile.STRUCTURAL_V1);
var task = new CheckedSchemaTaskSolver.Task(
    new ExpressionParser().parseExactTerm("x^4+4*y^4").expression(),
    List.of(), StructuralTaskObjective.FACTORIZATION.goal(), budget);
var result = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
var update = knowledge.learnBatch(lessons, validationTasks, acquisitionBudget);
knowledge = update.next();
var saved = knowledge.save(Path.of("knowledge"));
```

The preparation procedures are declared programmer-supplied capabilities. Their
outputs, not a handcrafted Sophie-Germain/Brahmagupta formula, become training
observations. Residual balancing examines bounded pair/sign combinations and
checks exact zero residuals. Its current profile handles four or six exposed
quadratic source components; output and combination limits remain explicit.
The new exact-factor partition is available through the actual task frontier.
General neutral insertion and user-proposed factor transfer are available in the
construction API but are deliberately not all sprayed into every search state.
This first profile is not an autonomous learner of arbitrary auxiliary-selection
tactics or a general nonlinear factorizer.

Candidate generation uses the existing exact-theory evidence mechanism with a
private issuer, source/target binding and exact work receipt. A construction edge
is not disguised as one primitive rewrite. Independent replay regenerates the
bounded procedure from the actual source and compares its evidence and output.
Both batch and the allowlisted registered incremental provider contracts are
supported. The incremental adapter charges the complete bounded atomic batch,
including candidates that are not subsequently consumed; it never calls its
finite incomplete relation exhaustive. Search and final replay share the task
budget. No gate or verifier has been relaxed.

## Goals, generations and persistence

`StructuralTaskObjective` gives stable typed identities to `FACTORIZATION` and
`SUM_OF_TWO_SQUARES`. A nontrivial factorization requires two mathematically
nonconstant polynomial factors. A square-sum requires two squares with
nonconstant polynomial bases. Unit factors, semantic zero padding and mere
identity-equivalence do not suffice. These are structural success criteria, not
proofs of irreducibility or global optimality. Generic square-preparation
landmarks allow useful unfinished paths to be recognized.

Automatic routing supports these named goals as well as the original node-count
goal. Arbitrary callbacks still fall back conservatively. Structural routing uses
only the root/child operator shapes and the declared goal; it does not use names,
expected outputs or identities. The routing policy is fitted on validation data,
so that data is not presented as an independent final test.

The construction profile is persisted in strict snapshot revision v2. Revision
v1 remains byte-for-byte canonical for legacy profiles. Both restorations
independently re-prove the stored mathematical schemata. Unknown revisions or
profiles are rejected, and the same assumptions and inventory binding apply.
Saving uses the unchanged non-overwriting content-addressed storage contract.

The opt-in formation path can learn from an independently replayed, within-budget,
unconditional useful prefix even when its full task is unsolved. It can also
learn from one checked non-learned exact construction step; an already learned
single step is not counted as a new theorem. Legacy completed-only formation is
unchanged. Candidate validity is not enough for activation: the existing benefit
and nonregression gate still applies. In the recorded partial-prefix test, a
correct schema is formed but rejected by the cumulative validation gate because
it has no validated benefit. That paid rejection is intentional, not reported as
an improvement.

The cumulative library remains the existing bounded unconditional polynomial
schema library. Conditional multiplicative constructs are checked by the direct
API but are not silently promoted as unconditional learned schemata. Solving does
not mutate knowledge; learning still requires an explicit frozen-parent batch.

## Executed development results

The real generic procedures now solve both raw historical tasks in the shared
task solver. Actual solutions are generalized by the existing learner and saved.
No final historical answer is inserted into the rule inventory.

| Query, matched successful quality | Same construction baseline | Learned automatic path |
| --- | ---: | ---: |
| `m^4+4*n^4`, nontrivial factorization | 34,508 | 481 |
| `(m^2+n^2)*(r^2+s^2)`, square-sum representation | 27,045 | 378 |

These are logical application costs including independent replay, not elapsed-time
factors. Acquisition and loading are additional. On `(u*v)^4+4*w^4`, the baseline
is inconclusive after 7,247 units whereas the learned path solves in 577 units.
This is a capability-transfer observation, not a matched-success speed ratio.

A separate two-generation campaign learns factorization first, then residual
composition while retaining the earlier schema. Both generations use genuinely
executed training and validation arms. After saving/reloading, a 32-query stream
contains eight rounds of the two solvable historical shapes, an already factored
expression and an unsuccessful `u^2+2` case. All arms solve exactly 24 of 32 tasks.
They have the same task budgets and construction capability set. No result cache
is used. The literal names vary, but the stream is only four structural templates,
not 32 independent mathematical families.

| Arm | Logical total including its acquisition and session setup |
| --- | ---: |
| Construction baseline | 506,872 |
| Learn the first capability and freeze | 316,450 |
| Learn both capabilities and reload | 163,881 |

The cumulative total is 153,329 acquisition/validation/preparation units,
1,424 restore/setup units and 9,128 query units. This is 67.7% less declared work
than the equal-capability baseline on this development stream. Unsuccessful and
already simple tasks remain present and slightly more expensive under routing.
A second JVM loaded the saved generation and used both schemata on compound
substitutions without retraining. The second generation preserves earlier
knowledge but does not demonstrate an invented hierarchical residual tactic.

These are development cases already discussed publicly in the project. There is
no sealed holdout, broad CAS superiority claim or complete CPU/memory/serialization
ledger. Per-operation weights are the existing logical model plus explicit new
construction charges. The old 512-query comparison remains unchanged at
225,536 / 229,264 / 186,706; these different workloads must not be conflated.

## Verification and reproduction

Twelve JUnit methods contain the actual executable assertions. The same test
classes retain command-line entry points; there is no second contract/wrapper
hierarchy:
three algebraic/domain tests, five goal/learning tests, three trust/budget tests,
and the mixed-stream campaign. No production mathematical class was stubbed.
The unchanged nine task-solver contracts and original cumulative contracts,
including their budget boundaries, also passed with identical old results.
Four compiled negative controls were detected: removed construction attachment,
false structural success, discarded partial progress, and unpaid rejected domain
inspection. Initial construction/solver behavioral failures were retained;
subsequent mutations are additional controls, not invented original RED runs.

```bash
./gradlew --no-daemon :regelsuche-math-algorithms:test --tests '*AlgebraicConstructionTest'
./gradlew --no-daemon :regelsuche-learning:test \
  --tests '*StructuralConstructionTest' \
  --tests '*StructuralConstructionSafetyTest' \
  --tests '*StructuralLearningStreamTest'
./gradlew --no-daemon --no-configuration-cache ciCheck
```

Locally, 865 real production sources from seven modules were compiled with real
Jackson dependencies. Only Java 21 was installed: a separate, unpublished source
copy adapts three existing `ScopedValue.call` signatures. The feature sources
and checkers are the actual executed sources. The test methods were run directly in a separate verification copy that removes
only JUnit imports and annotations. This does not reproduce JUnit discovery or
timeout enforcement, and is not a complete Java-25/JUnit build.
The Gradle wrapper download failed because services.gradle.org could not resolve.
The complete hosted build, original JDT complexity check, coverage, and independent
code review remain outstanding. The original implementation was retained as a local
patch while GitHub writes were unavailable. Publication preparation subsequently
rebased that patch onto `7cb836ec3b7ec1f326949ab8b78f2ce3979d572f`, whose
complete source tree was reconstructed and matched byte-for-byte by Git tree
identity. All production/test files remained identical to the previously tested
patch. Fresh direct contracts, all four mutations, the 32-query stream, old
regressions and a separate-JVM restoration reproduced the retained results.
A separate javac-tree complexity diagnostic inspected changed production files;
it is not the original JDT gate. No hosted CI success is inferred from these
local checks, and no Java-21 compatibility edit is part of the feature diff.

## PR #1125 quality-gate repair

The first hosted run (`41109808622`, head `dc0b2d2`) stopped at
`:ai-knowledge-verification:aiKnowledgeCheck`: the count-based
`estimatedContextTokens` trend increased by 15,805 against an unchanged limit
of 15,000. This is not a measured tokenization or a failing mathematical test.
The six other verification jobs succeeded, but that does not qualify the
interrupted Gradle authority.

The redundant contract/wrapper pairs are consolidated into four test classes
while retaining the same twelve JUnit test identities, assertions and timeouts.
Their command-line entry points still execute those assertions and the separate
JVM restore check. Design, implementation plan and execution history are retained
below instead of in two additional documents. No production source, quality
threshold, baseline, workflow or previous regression is changed by this repair.
The original CI weights project 920 fewer estimated tokens (four test types and
two documents), hence an increase of 14,885. Only a fresh hosted extractor run
can confirm the complete gate; this arithmetic is not that confirmation.

## Consolidated design


## Approved intent
Connect existing preparation/composition capabilities to cumulative task learning.
Support both additive and multiplicative auxiliary construction, not historical
answer rules. Keep independent mathematical checking, explicit assumptions,
finite budgets, stable generations, and the same improved baseline.

## Design
1. A typed finite `AlgebraicConstruction` API constructs additive complements and
   partitions, multiplicative complements and partitions, and factor transfers.
   The auxiliary is an expression, not an unbound pattern variable. Its domain is
   bounded scalar rational expressions. Existing rational-equivalence checking
   must reject multiplication/division by zero and missing nonzero premises.
2. `StructuralTaskObjective` gives factorization and sums of squares stable,
   versioned task identities. Structural evidence alone is never equivalence.
3. Opt-in `TaskConstructionProfile.STRUCTURAL_V1` augments the EXISTING typed
   frontier with existing square exposure, completion, factorization and exact
   residual composition. Candidates are source-only, regenerated and independently
   checked; no named historical answer is installed. Raw neutral insertion is an
   explicit diagnostic/construction API, not unbounded default branching.
4. The same capability profile is available to baseline and learned arms.
   New snapshots bind the profile; old v1 snapshots/default behavior stay intact.
5. In the opt-in profile, independently replayed improving partial paths and
   algorithmic construction steps can supply examples to the existing schema
   generalizer. Single already learned applications alone remain non-novel.
6. Work receipts include generation and repeated verification. Structural limits
   bound atomic work; an exceeded query budget is never reported as solved.
   These are declared logical receipts, not complete CPU/heap accounting.

## Out of scope
No noncommutative matrices, radicals/branch identities, universal induction,
unbounded auxiliary generation, or claim of autonomous strategy invention.
The residual program is an existing declared strategy; learning its generated
transformations is not learning that strategy from nothing.

## Acceptance
Real production tests must cover exact rational and large-integer arithmetic,
zero/symbolic divisor rejection and explicit-premise admission, useful product
construction, historical source-only solutions, reusable learned schemata,
partial-progress learning, immutable restoration and unchanged old regressions.
No CI quality gate, baseline fixture or original diagnostic is weakened.

## Consolidated implementation plan and execution record


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make bounded additive/product construction and existing structural procedures usable in persistent task learning.
**Architecture:** Extend the existing task solver/provider/verifier pipeline and existing checked-schema generalizer. Keep mathematical authority separate from source-only construction and routing.
**Tech Stack:** Repository Java 25, existing exact rational/polynomial kernels and JUnit.
**Spec:** [Consolidated design](#consolidated-design) in this document.

## Global constraints
No second search kernel or CAS; no named historical solution rules; unchanged legacy defaults and fixtures; exact domain checks; paid failures and verification.

## Review focus
- An auxiliary equal to zero, even with a forged nonzero assumption, must fail.
- Structurally plausible no-ops/unit factors/constant squares must not meet a goal.
- Loaded profiles cannot silently acquire new capabilities or overwrite old snapshot identity.
- Partial learning cannot use an unverified or over-budget path or infer impossibility from failure.
- All candidate/replay costs and finite truncation remain visible.

## Tasks
- [x] Test and implement typed, bounded additive/multiplicative construction with domain guards.
- [x] Test and implement stable structural objectives and opt-in existing-method provider.
- [x] Bind construction profiles through solver preparation, cumulative updates and snapshots.
- [x] Extend existing formation with independently checked useful partial paths and constructive steps.
- [x] Execute historical, product, persistence, budget and negative controls; run old contracts unchanged.
- [x] Inspect diff, publish PR #1125 with exact verification limits and recorded results.

## Execution ledger
- Isolated archive checkout on a new feature branch; main remains untouched. Archive is the retained checked-proof source of PR #1123. Current main was read live before work.
- Full Gradle verification attempted: wrapper download unavailable (DNS). Real production sources compile in a separate Java-21 compatibility copy; no product/toolchain changes are proposed.

- The first end-to-end learned run exposed missing allowlisted incremental construction admission. Added a registered cursor which charges its entire bounded atomic batch; no native admission was weakened.
- A compound Sophie input is not solved by the generic baseline in the diagnostic budget. Kept that negative result and added a separate matched-quality raw-input comparison instead of pretending a speed ratio.
- Rejected domain projection initially lost its work charge; added finally-based accounting and a regression which fails the former code.
- Full partial-path formation works; one validated partial campaign remains unhelpful and is correctly rejected. No success claim is inferred merely from a new theorem.
- The real 32-query stream has equal 24/32 success in all arms and paid totals 506872 / 316450 / 163881. Fresh-process reuse and unchanged legacy results rechecked.
- GitHub publication: discovery of create/patch/full connector actions found read-only capabilities; installed GitHub plugin confirmed, CLI absent and direct GitHub DNS unavailable. Deliver a git-apply-verified patch and evidence; no remote commit/PR/CI claimed.
- Review: full diff inspected locally. No independent reviewer/subagent or hosted full verification was available.

## Publication continuation
- GitHub write actions became available again. The latest main is
  `7cb836ec3b7ec1f326949ab8b78f2ce3979d572f`; its complete reconstructed
  tree matches `b503d821be49ea96e64a7d0dec635d42820d1893`. The patch applies
  cleanly, with no production/test source changes from the original tested patch.
- Fresh compilation: 865 real production sources. Direct new and legacy
  contracts, all four compiled mutations and separate-JVM restore passed.
  The 32-query totals remain 506872 / 316450 / 163881 at equal 24/32 success.
- A separate javac-AST diagnostic checked complexity; original JDT/Java-25 CI
  and independent review remain required. Gradle download was attempted again
  and remains blocked by DNS. No gate, baseline or workflow is changed.
- Publish on an independent branch based directly on that main, not on a
  pending PR. Keep draft until the full project verification and review finish.

- Quality-gate repair: consolidate duplicate test entry types and documentation, preserving production bytes and all behavioral assertions. Full hosted verification remains authoritative.
