# Public DIRECT/V4 product qualification (#745 F)

The new authority is `regelsuche.safe-runtime-product-qualification/v1`, with
configuration `direct-v1-vs-safe-preparation-v4-actual-runtime-v1`. It measures
only a fixed public characterization corpus through the delivered application.
The previous V2 experiment, schema, verifier, task and evidence remain frozen.
The default profile and the product authorization clock are unchanged.

## Architecture and alternatives

A Python runner drives actual `App.main` processes (`transform --runtime-request`,
`transform --runtime-replay`, and `serve` with `/api/search`). A separate verifier
reconstructs the decision from retained canonical artifacts. This avoids a new
production diagnostic subsystem and the circular dependency that an experiment
module importing the application adapter would create. A Java application-side
runner would simplify codec reuse but add production APIs for fixture setup.
A test-classpath-only report would be smaller but would not demonstrate the
installed application boundary. The installed-application runner is selected.

The runner receives a trusted application distribution, preverified public
fixture inputs, the fixed case manifest, an exact source revision and an output
directory. Requests receive source-side fields only. Expectations and scoring
remain in the public manifest and are never sent to the application.

## Frozen inputs and criteria

Before the first measured comparison, commit the public manifest with explicit
DIRECT_V1 / SAFE_PREPARATION_V4 profiles, the runtime artifact/adapter/work and
V4 coordinator/successor/work identities, all nine preparation limits and the
primitive/theory/total-work budgets. Ordinary cases use 200000 total work units,
4 primitive rewrites and 200000 theory work units. A declared zero-budget control
uses zero total work for both profiles. Selection and support IDs are identical
within each pair; every pair retains the complete visible rule inventory.

The small public corpus covers ordinary direct addition, native square
preparation, nested guarded direct and prepared positive/negative controls,
retained extra assumptions, a typed equation system, a visitor-dependent plugin,
an authorized pattern and program, and explicit technical, unsupported and
budget-inconclusive controls. The plugin and learned input context is identical
for all cases; only the declared request selection varies.

Each profile retains CLI analysis, HTTP analysis, fresh CLI import/replay and
fresh HTTP import/replay as exact canonical artifact bytes. All four observations
must agree. Scalar comparison uses the selected principal and its concrete
result, retained assumptions and complete primitive/theory execution. Typed
results retain their relation, full matrix artifact and concrete downstream
verification; they are not converted to scalar equality.

A SAFE gain requires a declared principal candidate that DIRECT does not retain,
complete successful replay on both routes, and no technical failure, unsupported
baseline, inconclusive budget or refused work reservation on either route.
The nested DIRECT support candidate is not mistaken for the prepared principal.
Expected fault and budget controls can satisfy their characterization assertions
but can never contribute a gain. Unexpected faults disqualify the report.

Qualification requires all declared expectations and invariants, no loss of
common direct candidates or retained assumptions, and at least one legitimate
SAFE-only principal. Candidate counts and all setup/analysis/verification work
are recomputed from raw material. No supplied success or gain flag is trusted.
The report measures selected versus full visible inventory coverage. Incomplete
public coverage retains `KEEP_OPT_IN_BOUNDED_PUBLIC_COVERAGE`; failed evidence
retains `KEEP_OPT_IN_QUALIFICATION_FAILED`. The report never changes a default.

## Public learned contract inputs

There are no checked-in retained authorization roots. Reuse the existing public
RewriteProgram contract exporter, preserving its old default output exactly.
A separate fixture wrapper creates `public-normalization-authority-contract-v1`
with subject revision `0123456789abcdef0123456789abcdef01234567`, issue time
2026-09-01, authorization time 2026-09-10, and fixed positive expiry 2100-01-01.
The matching expired control has expiry 2026-09-11. Each contains full pattern
leaves and program topology/replay/receipts, plus a runtime manifest.

This is a static public authorization-contract fixture, not an empirically
learned production model. Existing contract construction assembles public
`EvolutionFinalTestEvaluation` material; it does not open a protected corpus or
run a new protected FINAL TEST. Export and independent input verification happen
before measurement. The runner performs no authorization, only normal runtime
loading and fresh replay. The real product clock remains active: the expired
control must fail through both actual product surfaces. The declared executable
time window is after 2026-09-11 and before 2100-01-01.

## Reproduction and gates

Two independent clean checkouts build the same exact committed source and run
the same declared inputs. A digest-pinned container executes the same public
launcher offline. Each environment retains the actual runtime class-content
identity and application/plugin artifacts. Canonical outputs must be byte equal;
compiler-produced class differences are a reproduction failure, never stripped
from the evidence. Runtime portability uses the delivered class/JAR bytes and
requires no Git checkout during evaluation. Git is used only to prepare and bind
the source/build inputs. Logs, paths, ports and timing remain diagnostics outside
the canonical comparison.

Checkout-owned Gradle tasks run the producer and independent verifier; fullCheck
adds clean-checkout/container reproduction. Ordinary Maven tests retain adapter
and fixture contract coverage. Current-head complete Maven/Product/Docker,
Gradle, SymPy, JMH and final ciCheck remain mandatory integration evidence.
Docker is unavailable in the local agent environment, so container success can
only be claimed after its actual CI run. README, architecture, user documentation
and capability status are updated from the resulting measured evidence.
