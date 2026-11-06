# Structural construction and checked task learning

## V2: coefficient-derived factors and expanded square sums

`TaskConstructionProfile.STRUCTURAL_V2` adds two bounded capabilities to the
unchanged V1 provider set. The first projects a source-exact polynomial into
the existing `BinaryQuarticFactorizationEngine`, obtains only decompositions
accepted by `FactorizationVerifier`, and subjects the rendered expression to
the same source-bound construction replay and exact polynomial checking used
by the task solver. Engine, verification, projection and rendering work enter
the construction receipt. V2 projection and replay additionally charge bounded
monomial-product attempts even when later cancellation or a limit stops the
projection; a rejected multiplication may be charged conservatively before it
is performed. These remain declared logical units, not a complete runtime or
bit-operation measurement. No completeness or irreducibility claim is made.

For example, the engine can derive a nontrivial product for
`x^4+4*x^3*y+6*x^2*y^2+7*x*y^3+2*y^4` from coefficient constraints. This is
not the general Ferrari procedure: the adapter supports two variables, integer
coefficients, homogeneous degree four, nonzero extreme coefficients and two
quadratic factors within the existing engine's coefficient and work bounds.
Other domains and bounded misses remain inconclusive. In particular, rational
coefficients, nonhomogeneous quartics and arbitrary symbolic coefficients are
not supplied by this adapter.

The second capability exposes the already present residual composer to an
additive-root source, so an expanded four-term sum can be processed without
first recovering its original product syntax. The existing limits of four or
six exact square components and bounded composition still apply. Both V2
capabilities use the existing frontier and are available to both baseline and
learned arms. V1 and older snapshot semantics remain unchanged; V2 is persisted
under its explicit profile identity.

The integration test also learns a checked schema from two renamed instances
of one quartic and applies it after restore to a composite substitution. This
is evidence for reuse of that schema, not autonomous invention of a general
coefficient-solving plan or a broad performance study.

An additional deterministic regression generates 16 distinct coefficient
systems by expanding products of small quadratics, supplies only the expanded
sources to the task solver, and independently checks every returned product.
With one search step, V1 solved 0/16 and V2 solved 16/16 in the recorded run.
These are related generated cases within one bounded family, and the arms
have different supplied capabilities; the observation is a capability
comparison, not a learned-speed or elapsed-time comparison.

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

## Source-derived auxiliaries in the shared frontier

The opt-in `STRUCTURAL_AUX_V1` profile extends `STRUCTURAL_V2` with bounded
additive-complement and exact-factor proposals and square recognition in the
existing typed task frontier. It does not change the persisted meaning or
scheduling of `STRUCTURAL_V1` or `STRUCTURAL_V2`. The same profile is
attached to baseline and learned solvers; no extra learner or frontier is used.
The selection of general operations is supplied, not learned. The profile favors
shorter search paths and ranks square recognition and factoring ahead of further
construction. This is a development policy, not an invented proof strategy.

The existing `AlgebraicConstruction.explore` retains its original order and
conditional rational behavior. The separate `explorePolynomial` opt-in counts
bounded monomial products and accepts only unconditional polynomial operations.
For additive complements it proposes cross-products of pairs of visible square
bases, not unrestricted neutral insertions. For exact factor splits it considers
proper subpowers and source fragments whose exact polynomial value is
nonconstant; the current quotient operation supports monomial divisors. A
candidate can still have a constant quotient and serve as an intermediate step;
the factorization goal itself requires two nonconstant factors. No historical
result, formula, or unbound symbol is an input. Candidate formation and the existing source-bound construction provider
charge polynomial products during creation, admission and replay; the new
profile also counts products in the built-in structural objective. Square
recognition uses the existing
subtree adapter and an explicitly enabled product-factor matching mode of the
existing conservative operator. Its default mode remains unchanged.

A source-only development test gives the frontier only the input expression,
the general goal of nontrivial factorization, a fixed budget and four generic
providers: square exposure, additive complements, square recognition, and
difference-of-squares factoring. It does not pass a target expression or an
ordered path. After the run, an exact polynomial checker verifies the resulting
product and the retained witness identifies the operations actually used.

| Source | Result | Search and replay work | Witness steps |
| --- | --- | ---: | ---: |
| `x^4+4*y^4` | nontrivial product | 52,894 | 6 |
| `16*x^4+4*y^4` | nontrivial product | 1,279,621 | 7 |

These are two related development examples, not independent held-out
mathematical families. Removing the auxiliary provider leaves the same
restricted comparison inconclusive; it does not prove global impossibility.
The full solver also retains the existing direct construction, so these two
tests do not establish a new success over the full V2 solver. A separate full
solver comparison uses the same per-task budget in both profiles:

| Input | V2 | AUX profile | Declared work V2 / AUX |
| --- | --- | --- | ---: |
| `12*x^4` | inconclusive | nontrivial factorization | 1,562 / 6,513 |
| `x^4+4*y^4` | solved | solved | 28,002 / 31,839 |
| `u^2+2` | no solution found | no solution found | 280 / 601 |

The new profile adds a bounded capability in the first development example but
does more work in the two controls. These numbers use the profiles' respective
work contracts: only the new path includes the new product-work accounting.
They are not an equal-accounting speed comparison, JVM time or memory measurement.

The new factor construction can also supply a checked lesson to the existing
learner. A development run learns from `12*x^4` and `20*y^4`, validates on
`28*z^4`, restores the promoted knowledge and then handles eight further
coefficients of the same `k^4` template. Restoration here is in the same JVM
from canonical JSON; file I/O and process restart are not measured. Both arms use the **same** auxiliary
profile and the same per-task budgets. The stream also includes an already
factored input and a bounded unsuccessful input, with identical outcomes in both
arms. On the eight `c*k^4` queries both arms reach the nontrivial goal and the
learned arm applies its checked schema. The already-factored control needs no
learned step.

| Stream work | Baseline | Learned |
| --- | ---: | ---: |
| Declared work including acquisition and restore | 52,719 | 30,074 |

The learned total includes 27,197 units for lessons, formation, preparation,
validation, selection and split checks, 616 for restore, and all ten query costs. This is
about 43% less declared work for this specific development stream, not a result
across independent mathematical families. Some existing acquisition and proof
paths still do not count polynomial products separately, so this is not a
complete total-work comparison. Serialization, external test checks, JVM time
and memory are not covered by the logical work model. Autonomous plan
invention, full historical coverage, Euclid and Ferrari remain open.

## Source-only perfect-square tasks

The opt-in `STRUCTURAL_SQUARE_V1` profile adds exact rational square exposure
and recognition to the existing task frontier. Its `PERFECT_SQUARE` objective
asks for an explicit square with a nonconstant polynomial base. It receives no
expected expression or ordered proof. Each generated step is checked against
the source with exact polynomial arithmetic, and the selected path is replayed
before a result is reported. The supported task domain permits literal,
nonzero denominators; symbolic denominators are not admitted.

The original square recognizer remains available with its previous matching
behavior, with a work observer enabled only in this new profile. The rational
recognizer additionally accepts exact fractions and bounds its scalar
intermediates to 1,024 bits. Rational monomial exposure does not discard a
quotient merely because its normalized value is an integer: it first checks,
with an accounted applicability test, whether the existing integer provider
already covers the source syntax. Existing profiles and stored profile names
keep their original meaning. Both recognizers may inspect the same expression,
so the new profile can do additional work on cases it already solved.

The five square-completion inputs of the frozen historical atlas are solved by
the cumulative task solver below. These are development examples, not an
independent held-out benchmark. The atlas's frozen production-policy report has
not been rerun with this separate profile and must not be labelled 12/12 on the
strength of these results.

| Source | New-profile work | Witness steps |
| --- | ---: | ---: |
| `x*x+2*x*y+y*y` | 6,439 | 3 |
| `x*x+y*y+2*y*x` | 6,413 | 3 |
| `y*y+(2*x)*y+x*x` | 9,591 | 3 |
| `x*x+3*x*a+2.25*a*a` | 12,200 | 3 |
| `x*x+(4/3)*x*y+(4/9)*y*y` | 15,298 | 3 |

The regrouped input produces `(y+x)^2`: this is the requested square up to
commutativity, but is not a syntax-exact match for the atlas target `(x+y)^2`.
The task goal accepts either representation.

The earlier AUX profile already solves the first three development inputs.
For the last two, a regression compares AUX and the square profile with the
same fixed per-task budget: AUX finds no square, while the new profile does.
The new path also handles the univariate input `x^2+(4/3)*x+(4/9)`; a nearby
non-square and a tiny budget are negative controls. These comparisons establish
bounded outcomes, not impossibility outside the budget or a speedup between
profiles with different accounting.

The existing learner was also exercised with two lessons, a distinct
validation expression and eight later inputs from the same shifted-square
family. The retained schema is symbolically checked, restored from canonical
JSON in the same JVM and actually applied to all eight queries. An already
square input and an inconclusive input are included in both arms with matching
outcomes and quality. With both arms using the square profile, the development
stream records 148,047 baseline work units and 97,025 learned work units,
including 65,626 for acquisition and 726 for restoration in the learned arm.
The figures do not include file I/O, process restart, JVM time or memory, and
some existing learning and proof paths do not separately count polynomial
products. This is therefore evidence for reuse on one related family, not a
complete total-cost or cross-family learning claim.

The new square profile validates the source and each replayed intermediate in
the bounded total rational-polynomial domain and checks each edge by exact
polynomial projection. Selecting the direct `PERFECT_SQUARE` objective without
a construction profile uses the same domain, step checks and product-work
accounting. The square evaluator also reports product work when supplied as a
callback; arbitrary custom objectives remain responsible for their own work
reports. A callback does not retain the enum identity: callers must select a
strict profile for its domain and per-step checks. Without that profile, legacy
custom-objective behavior does not establish those checks. This matters for
user-configurable rewrite rules: a
replayed rule is not automatically an identity. Symbol names must also follow
the parser's identifier grammar, so formatting an AST cannot reinterpret a
single symbol as an expression. As in the checked-schema polynomial domain,
zero powers use the explicit convention `0^0 = 1`; this differs from other
project components that treat that expression as undefined. The source inspection and both initial and
independent final step checks are charged; an unsupported source raises an
exception carrying the observed inspection and routing work, with incomplete
accounting for a solve invocation. Source inspection is an atomic operation; if it together with the
root node assessment exhausts the budget, the supplied objective is not evaluated.
This experimental checked path does not
support an already active native retention-observation scope; solving and
learning reject that scope before routing or split checks. Legacy construction profiles retain their
original behavior; they do not gain this new checking contract implicitly.

The set and priority of general construction providers were supplied by the
implementation. The system did not invent that strategy or receive the
historical target. The larger historical catalog, including Euclid and the
general Ferrari method, remains open.

## The frozen catalog through one source-only task solver

The existing 14-case historical corpus is also exercised by
`HistoricalCatalogTaskCoverageTest` through one cumulative task solver and the
opt-in `STRUCTURAL_EXPANSION_V1` profile. The test gives each task its source,
empty assumptions and one of the general goals **perfect square**,
**nontrivial factorization**, or **expanded and collected polynomial**. It does
not give the solver a target expression, corpus witness, or ordered path. The
target is inspected only after the search. The selected general providers and
their scheduling were supplied by the implementation, not invented by the
search.

All 12 historical positive inputs reach their declared structural goal with a
nonempty replayed witness and an exact polynomial equivalence recheck. Nine
results happen to be syntax-equal to the stored target. The remaining three
are equivalent representations: `(y+x)^2` instead of `(x+y)^2`, and two
expanded results which use powers instead of repeated products. One of the
nine matches is Sophie Germain, whose corpus relation is value-equivalence;
therefore eight of the eleven cases labelled `SYNTAX_EXACT` meet that exact
spelling in this run. **This is 12/12 under the new task contract, not 12/12
under every original atlas relation and budget.**

| Frozen input | Declared work | Witness steps | Exact target syntax |
| --- | ---: | ---: | --- |
| complete square | 6,439 | 3 | yes |
| reordered square | 6,413 | 3 | yes |
| regrouped square | 9,591 | 3 | no |
| scaled square | 12,200 | 3 | yes |
| fractional square | 15,298 | 3 | yes |
| difference of squares | 4,595 | 3 | yes |
| difference of squares with powers | 2,734 | 1 | yes |
| common left factor | 782 | 1 | yes |
| common right factor | 784 | 1 | yes |
| expand binomial square | 2,331,979 | 7 | no |
| expand difference-of-squares product | 125,405 | 2 | no |
| Sophie Germain | 43,722 | 5 | yes |

The positive cases use the same task budget: 32 primitive steps, search depth
8, 10,000,000 theory units, 512 states and 10,000,000 total work units. The
negative control is independently confirmed unequal to its stored target and
does not reach the square goal with its smaller 200,000-unit budget. The policy
control also reaches the general expansion goal, but its result and budget do
not reproduce the original syntax-exact, three-state policy diagnostic; that
original control is still evaluated separately by the atlas.

For example, the source-only Sophie-Germain task retains the following five
individually replayed steps. The last three lines are displayed in ordinary
algebra notation; the test also prints each actual source, rule and target:

```text
x^4 + 4*y^4
  -> (x^2)^2 + 4*y^4
  -> (x^2)^2 + (2*y^2)^2
  -> (x^2 + 2*y^2)^2 - 2*x^2*2*y^2
  -> (x^2 + 2*y^2)^2 - (2*x*y)^2
  -> (x^2 + 2*y^2 - 2*x*y)*(x^2 + 2*y^2 + 2*x*y)
```

All steps, including configurable primitive rules and the final independent
replay, pass the checked square profile's per-edge domain and exact identity
validation. Source-bound replay alone does not prove an arbitrary rule.
The construction steps carry source-bound replay and exact identity checks;
their internal algorithms are not expanded into a complete textbook proof.
The expansion steps use the existing distributive and normalization rules.
Their observed `RetainedOperation` execution and validation events are added
to generation and replay work. A failed provider exposes its locally observed
work while marking the unfinished search accounting incomplete. Nested
`RetainedOperation` scopes are rejected before these providers run because
their current accounting cannot safely preserve the enclosing retention scope.

The default expansion profile selects these providers only when the goal is the
declared `StructuralTaskObjective.EXPANDED_POLYNOMIAL` enum instance. A custom
or wrapped objective does not carry that goal identity.
`STRUCTURAL_EXPANSION_BRIDGE_V1` also makes them available later in the
search for other goals. The latter is an explicitly different policy and its
extra lanes can change work and bounded outcomes. The default profile was
checked against the square profile on square and factorization tasks with
identical query results and declared search work. Profile construction itself
is not included in those figures. As elsewhere in this experimental path,
some existing learning/proof operations and actual memory, file I/O and JVM
time remain outside the declared work model.

The existing learner can also retain a checked expansion schema: two lessons
and a separate validation input promoted one schema. On one further related
development expression, the baseline exceeded the 10,000,000-unit task budget
while the restored learner solved it in 8,220,946 units using two learned
applications. Acquisition cost 39,934,791 units before that query; no
break-even or cross-family advantage is established by this example.

To reproduce the shared catalog test and its step log with the checkout's Java
25 Maven toolchain:

```sh
mvn -pl regelsuche-autopilot -am -Dtest=HistoricalCatalogTaskCoverageTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
```
