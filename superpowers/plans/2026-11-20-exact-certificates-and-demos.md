# Exact certificates and research delivery plan

**Spec:** [accepted specification](../specs/2026-11-20-exact-certificates-and-demos.md)

**Goal:** Deliver the audited mathematical capabilities, usable demonstrations,
correct documentation and a consolidated Primachsenraum review draft.

**Architecture:** General algorithms and certificate checking live in
`regelsuche-math-algorithms`; typed SDK adapters translate budget/cancellation.
The Workbench calls the core. Prime-specific chronology stays in the separate
Primachsenraum consumer. Published SDK and Sandbox status remain explicit.

## Global constraints

Use exact arithmetic and expected-source binding. Resource stops never certify
partial results. Keep core independent of SDK. Distinguish actual executions,
operation-count projections, finite proofs, conjectures and historical novelty.
Keep prior manuscripts immutable. Update `docs/research/strategies.md` in the
same delivery and regenerate its source references. No unsolicited publishing
or external messages.

## Task 1: Exact polynomial positivity

Implement a focused `positivity` core package: sparse rational polynomials on
bounded rational boxes, Bernstein coefficients, exact midpoint subdivision,
certificates and an independent replay verifier. Support at least univariate
and bivariate inputs, arbitrary rational coefficients and declared degree
bounds. A negative Bernstein coefficient means inconclusive, not a negative
polynomial. Check that subdivided leaves cover the original domain exactly.

Add exact rational symmetric Gram/Schur PSD checking with correct singular
pivot handling. A polynomial Gram certificate must separately establish
`p = z^T Q z` before deriving nonnegativity. Distinguish semidefinite from
strictly positive. Source substitution, corrupted coefficients, lost boxes,
wrong Gram identities, cancellation and budget exhaustion must be tested.

Use existing `ExactRational` and `IterationLimits`, thin typed SDK wrappers,
TDD with an observed failing test before implementation, then independent
small exact oracle cases and adversarial controls. New research documentation
must cite pinned source paths and state limitations. Root owns shared SDK
README and strategy index; supply a proposed strategy entry in the task report.

## Task 2: Hunter bound and finite proof DAG

Implement a separate focused core class using `CongruenceCoverage.Request`
and activated congruence semantics. For finite W, certify
`free >= |W| - sum |Ai| + sum_(ij in tree) |Ai intersect Aj|`, clipped at zero.
Construct a maximum-weight spanning tree from exact pair counts; verification
checks a valid spanning tree and exact counts independently. A nonoptimal tree
is still a sound certificate, and must not be called optimal unless checked.
Handle empty/one-rule cases, duplicates, noncoprime moduli, signed starts,
activation thresholds, all-blocked and empty intervals.

Add a small typed transportable finite proof DAG. Leaves use existing exact
coverage, Hall or the new Hunter certificates; each node has explicit premises
and a clearly sound finite deduction. Keep domain assumptions visible rather
than allowing arbitrary user-labelled claims. Reject source changes, cycles,
forward/missing references, invalid joins and nonpositive existence claims.
Prefer a small justified rule set over a speculative generic proof language.

Tests must include the finite set 0..29 blocked by multiples of 2,3,5:
singletons 15,10,6; pair intersections 5,3,2; Hunter lower bound 7; actual free
count 8; union bound 0. Compare independently enumerated small random cases and
existing Bonferroni; test adversarial certificates and resource stops. Add thin
SDK access, a focused reference document and reproducible output. Root owns
shared strategy index and SDK README; propose entries in the report.

## Task 3: Workbench demonstrations

Follow existing app/service/UI conventions to add live demonstrations of a
Hall deficit and Montgomery reduction planning using real core calls and
certificate verification. Make mathematical inputs, results and claim limits
visible. Enhance the pair-reduction demo to actually run its reduced algorithm
on 100,000 deterministic input values; verify against a direct pair oracle on
a small prefix. Clearly label the 4,999,950,000 pair count as a count of the
baseline operations, not an executed or timed baseline.

Use existing backend tests and meaningful browser-flow tests. Capture actual
desktop and mobile screenshots for documentation and inspect them. Update
`docs/demo-gallery.md` and relevant Workbench user guides. Root owns SDK README,
global strategy index and availability matrix. App must call core, not a new
SDK dependency. New positivity/Hunter demos may be added once APIs are ready,
but the three specified demonstrations are the minimum complete deliverable.

## Task 4: Core architecture and documentation reconciliation

Move mathematical paired-bilinear candidate generation from SDK-owned logic
to a reusable core algorithm through a typed adapter. Preserve Java BigInteger
and modular eligibility in SDK and acceptance in the existing independent
polynomial proof. Test core proposals, SDK preservation and existing outputs.

Repair release instructions and add one source/release/Workbench/Sandbox
availability matrix. Update audit and strategy entries with evidence, retain
historical benchmark revisions, and avoid implying new source capabilities
are available from Maven Central 0.5.3 or integrated in Sandbox.

## Task 5: Primachsenraum comparisons and review draft

Fetch a version-bound private checkout through the available GitHub connector.
Keep general methods in Regelsuche. Compare sieve bounds on truly unoccupied
target classes under recorded K0 assumptions, including p=719 targets
287,313,658 at K0=24618 and the first actual class-658 witness at 49550.
Include the strict-before-49550 negative control and distinguish an exact
chronology oracle from a theorem under incomplete earlier knowledge.

Consolidate a new review revision without altering v4 or the existing v5
baseline. Include the beta bracket
`0.77274572771007138835 < beta < 0.77274572771007138836`, finite factor bound
`g <= 72,461,708,495`, and shift-three excess bound
`0 <= F3(x3)-1 < 10^(-10,000,000)` with exact stated assumptions and citations
to repository proofs. Verify numeric certificates, document remaining open
claims, and update research status/navigation. No automatic external sending.

## Task 6: Review, verification and repository delivery

Review each independent task for specification and correctness, then review
the integrated branch. Run focused mathematics/SDK/app tests, documentation
reference checks, private consumer tests and manuscript build/checks. Rebase
against current remote changes without overwriting concurrent work. Publish
focused pull requests with exact test evidence and inspect CI results. If a
gate is blocked, identify the actual failing gate and leave a reviewable branch
instead of reporting success.
