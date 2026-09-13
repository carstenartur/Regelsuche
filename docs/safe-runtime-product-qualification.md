# Public DIRECT/V4 product qualification

The additive authority `regelsuche.safe-runtime-product-qualification/v1` compares
`DIRECT_V1` and `SAFE_PREPARATION_V4` through the installed application CLI and the
actual Workbench `/api/search` endpoint. Its configuration is
`direct-v1-vs-safe-preparation-v4-actual-runtime-v1`. The older #967 V2 experiment,
schema, verifier and evidence remain frozen under their original identities.

## Criteria fixed before measurement

[The 14-case manifest](../config/qualification/safe-runtime-public-v1.json) was
committed in the local implementation history at `887283d3e7` before the first measured comparison. Its exact SHA-256
is `3915a0630ee9f2a58a2f17a282a60a0a43bbf8d66e668a5d6cb0389964bf86d9`.
Changing these bytes requires an explicit successor contract; the verifier
rejects an altered manifest even when a producer supplies new hashes.

The exact referenced local commits are retained in
[a Git bundle](evidence/safe-runtime-public-local-history.bundle), with a
[hash and ancestry receipt](evidence/safe-runtime-public-local-history.json).
Publishing the integrated tree through GitHub's commit API creates different
commit IDs, so the bundle preserves the original source graph for inspection.
In a clone containing the receipt's three prerequisite commits, run
`git bundle verify docs/evidence/safe-runtime-public-local-history.bundle`, then
`git fetch docs/evidence/safe-runtime-public-local-history.bundle refs/heads/codex/issue-745-product-qualification`.
This is retained local Git history, not an independently timestamped public
preregistration. The complete hosted integration remains a separate CI gate.

Each pair has identical source input, initial assumptions, selected rules,
preparation support, full visible inventory, primitive/theory path limits and
all nine preparation limits. Ordinary cases receive 200000 total logical work
units, 4 primitive rewrites and 200000 theory work units. The declared budget
control receives zero total units on both routes. Already executed learned
receipt revalidation remains charged even when that budget refuses setup.

A gain requires the declared principal to be absent from complete DIRECT
execution and available in complete SAFE execution. A support candidate is not
mistaken for that principal. Technical failure, unsupported or inconclusive
DIRECT execution, refused reservations and lost common candidates cannot create
a gain. This check includes every individual rule outcome, even when a support
candidate makes the aggregate status successful. A missing-guard control permits
only its declared principal's `UNSUPPORTED` outcome; additional unsupported,
technical or budget failures invalidate the comparison. Expected negative controls characterize their status and never count
toward improvement. Every declared expectation must pass, all common candidates
and assumptions must remain, and at least one legitimate gain is required.

## Measured bounded result

The first installed-app run and an additional independent fresh verifier passed
all 14 cases on 2026-09-13. There are 112 retained observations: both profiles,
both product surfaces, analysis and fresh import/replay. All four observations
for each profile/case are byte identical. Six rehashed export mutations and two
real-clock expiry controls are rejected through both surfaces. A separate test
forges coherent results, execution provenance, all four observations and the
whole report; it is rejected at independent actual product execution.
A correctly rehashed report that replaces only the source revision is also
rejected against the verifier's independently supplied expected revision.

| Observation | DIRECT | SAFE V4 |
| --- | ---: | ---: |
| Total charged logical work, including setup and verification | 1811 | 4587 |
| Native square principal on `4*x^2-y^2` | absent | replayed |
| Nested rational principal on `1+((a/b)+0)*(c/d)`, with both guards | absent | replayed |
| Common candidate regressions | 0 | 0 |
| Declared expectation failures | 0 | 0 |

The corpus additionally checks nested direct guards, missing-guard controls,
an irrelevant retained assumption, a typed equation system with exact solution
`[9/10, -1/2]` under `SOLUTION_SET_EQUIVALENCE`, visitor-dependent plugin execution,
an authorized pattern and an authorized rewrite program, and distinct technical,
unsupported and budget-inconclusive outcomes.

The full visible inventory has 69 entries in this fixed fixture context; 41 are
eligible for runtime preparation and 8 are selected by the public cases. The
remaining 61 stay visible, including disabled SymPy and direct-only plugin/program
sources. The result is `QUALIFIED_BOUNDED_PUBLIC_CASES` with
`KEEP_OPT_IN_BOUNDED_PUBLIC_COVERAGE`. SAFE uses more logical work in this corpus.
This evidence does not establish a faster profile, general rewrite completeness,
universal rule amplification, external novelty or a production default.

## Learned input authority and the real clock

`LearnedRuntimeProductFixtureWriter` exports the additive public contract fixture
`public-normalization-authority-contract-v1`. It derives from the existing static
public learned-pattern/program authorization fixture, with unchanged historical
writer output. Its subject revision is
`0123456789abcdef0123456789abcdef01234567`, issue time 2026-09-01, authorization
time 2026-09-10, positive expiry 2100-01-01 and negative expiry 2026-09-11, all UTC.
The executable comparison window is 2026-09-11 through just before 2100-01-01.

The two patterns and program retain full genome, validation, counterexample,
static public holdout, topology, replay and receipt roots. The existing independent
leaf/program verifiers run before measurement. The runner then only loads those
verified inputs; it performs no authorization or learning. Both profiles use the
same manifests and receipts. The ordinary product `Clock.systemUTC()` remains
active, and the expired input must fail through actual CLI and HTTP calls.

This material is an explicitly declared public **contract fixture**, not an
empirically learned production model. Constructing its existing static public
evaluation record does not open a protected corpus or execute a new protected
FINAL TEST. No flagship freeze or automatic default change is part of this work.

## Reproduction and retained files

From a clean committed checkout with Java 25 and the normal verification
prerequisites:

```bash
./gradlew --no-daemon --no-configuration-cache --max-workers=2 verifySafeRuntimeProductQualification
./gradlew --no-daemon --no-configuration-cache --max-workers=2 reproduceSafeRuntimeProductQualification
```

The first task installs the application, exports and independently verifies
public authority inputs, executes the corpus and independently recomputes and
replays the report. It is part of `check`. The second builds two detached clean
checkouts of the exact revision plus the digest-pinned
`Dockerfile.safe-runtime-qualification`, then runs the same public launcher in
the container with `--network none`. It is mandatory in `fullCheck` and `ciCheck`.
Missing Docker, a dirty source tree, a different revision, an extra/missing
artifact or any byte difference fails reproduction.

The verifier requires `--revision` from its caller. Gradle supplies the source
authority revision, clean-checkout reproduction supplies the checked exact commit,
and the container receives the same build argument. The report cannot authorize
its own source revision. Runtime implementation identity additionally binds the
actual delivered application and plugin bytes; it is separate from this source
provenance check.

The root is `build/reports/safe-runtime-product-qualification/`:

| Path | Retained authority |
| --- | --- |
| `inputs/` | Both complete learned input variants and manifests |
| `run/qualification.json` | Canonical recomputed decision, work, coverage, input and implementation hashes |
| `run/artifacts/` | Exact CLI/HTTP analysis/replay bytes and negative controls |
| `run/diagnostics/` | Stderr and actual clock-dependent rejection messages |
| `reproduction/` | Each environment's outputs, exact-byte comparison receipt and diagnostics |

The CI repository-verification artifact already retains these report paths.
Only `qualification.json` and the complete `artifacts/` tree enter canonical
comparison. Ports, temporary paths, stderr timestamps and wall-clock diagnostics
do not. Runtime class-content identities and actual application/plugin artifact
hashes remain in the comparison. Compiler byte differences are an honest
reproduction failure; they are never normalized away. Runtime evaluation/replay
uses the delivered JARs and requires no Git checkout.

Two physically separate clean checkouts of
`e71cb7bc6a51aee64ddad6b8790acd15552ebf46` each compiled all 56 required build tasks
with `--no-build-cache`, then passed the full public run and independent replay.
All 127 canonical output files matched exactly, with output-tree hash
`sha256:fb54a0342292118fd10eb5cc8ed2ab6d838046d7f0b737c0fff91eec4042f5c4`.
Their report hash is
`sha256:b44d550b9f2badb60cb6d5720c932ef0c050d5ae3e4034fd7b9c78fa629d0e43`.
The later verifier corrections preserve this result and reject the additional
source-provenance and hidden-outcome counterexamples.

Local endpoint qualification and clean-checkout reproduction are complete. The actual
pinned-container run and complete integrated Maven/Product/Docker, Gradle,
isolated SymPy/JMH and final checkout-owned `ciCheck` remain required CI evidence;
their success is not inferred from this local public run. The generated public
capability status therefore retains the explicit `EXPERIMENTAL` opt-in boundary.
