# Shared opt-in runtime for CLI and Workbench

Issue #972 adds `regelsuche.safe-product-runtime/v1`. Both `transform` and
Workbench search call this same adapter. Ordinary requests still select the
existing search. No FINAL TEST or decision to make SAFE the default is part of
this change. Historical SAFE_PREPARATION_V2 evidence is unchanged.

## Requests and product controls

Save this source-side request as `request.json`:

```json
{
  "schema": "regelsuche.safe-runtime-request/v1",
  "profile": "SAFE_PREPARATION_V4",
  "source": "4*x^2-y^2",
  "assumptions": [],
  "ruleIds": ["ast_square_difference_factor"],
  "preparationRuleIds": [],
  "includeSymPy": false,
  "maxWorkUnits": 200000,
  "maxPrimitiveRewrites": 64,
  "maxTheoryWorkUnits": 200000
}
```

Use `transform --runtime-request request.json` to write the canonical artifact
to standard output, then `transform --runtime-replay artifact.json` to reload
and independently execute it. The Workbench `POST /api/search` accepts
`{"runtimeRequest": <request>}` or `{"runtimeArtifact": <artifact>}`. A verified
HTTP replay returns `X-Runtime-Replay: VERIFIED`. Changed or stale evidence is
rejected with HTTP 409; invalid requests return 400. Do not combine these
envelopes with legacy search fields.

In the Workbench, select `DIRECT_V1` or `SAFE_PREPARATION_V4` under
**Expliziter Runtime-Vergleich**. Enter assumptions one per line. Candidate
rows display the selected profile and their retained assumptions. Export and
import use the same complete artifact as the CLI. The system input option
retains the typed representation, relation and exact downstream solution.

`DIRECT_V1` invokes the V3 direct occurrence stage and never invokes preparation
or the unresolved-principal V2 delegate. `SAFE_PREPARATION_V3` remains available
through the request/replay API with its original occurrence binding v1 and
occurrence work v2 identities. `SAFE_PREPARATION_V4` selects the explicit
`regelsuche.unified-safe-rule-preparation-coordinator/v4` successor. It preserves
the V3 base evaluation and adds bounded preparation at concrete nested
occurrences, with separate evidence and work. Historical V2 and V3 authorities
and their certificates are unchanged.

The shared visible inventory includes configured PluginRuntime rules, every
first-party RuleDomainRegistry domain, configured authorized learned rules and
programs, and the direct SymPy source. Conflicting rule IDs are rejected.
`ruleIds` selects a subset; omission or an empty array selects the full
inventory. `includeSymPy: false` explicitly disables that direct source. The
complete visible inventory, selection and coverage remain in the artifact
under both profiles. A rule without an admitted applicability schema remains
directly available through its original executor; it is not a SAFE principal.
Plugin-contributed rules, transformations and macros also retain their original
visitor-aware executor under both profiles, even when they declare a schema.
The inventory preserves their raw schema coverage and hash, and separately
records `runtimePreparationEligible: false` with
`PLUGIN_CONTEXT_REQUIRES_ORIGINAL_EXECUTOR`. Schema coverage alone cannot
authorize dropping the plugin context. These rules remain selectable for direct
execution but cannot serve as preparation support.

`preparationRuleIds` explicitly assigns selected, schema-admitted rules to
support the principal batch. The V3 authority requires support and principal
IDs to be disjoint. Support rules still execute directly under both profiles,
through a separately recorded direct V3 batch. The default support list is
empty; the native exact preparation registry remains available to SAFE.

For example, select `rational_multiply_fractions` and `ast_add_zero_right`,
assign the latter as support, and use `((a/b)+0)*(c/d)` with `b != 0` and
`d != 0`. SAFE retains the support-plus-principal lineage. DIRECT retains the
support rule's ordinary direct result. Removing `d != 0` prevents the guarded
principal result. Nested direct guard binding is characterized separately by
`1+(a/b)*(c/d)` under both profiles.

For `1+((a/b)+0)*(c/d)`, V4 prepares the guarded occurrence inside the outer
sum. Its retained path binds the original source, occurrence, assumptions and
lifted result; concrete replay checks the preparation and principal execution
in that context. Missing `d != 0` prevents this candidate. DIRECT and historical
V3 retain their original outcomes for the same request: neither produces this
prepared principal candidate.

The optional `preparationBudget` object binds all nine existing V3 limits:
`maxDepth` (default 2), `maxVisitedStates` (128),
`maxGeneratedTransitions` (512), `maxPrimitiveSteps` (4),
`maxExpressionNodes` (256), `maxSuccessorsPerState` (32),
`maxMatchResults` (32), `maxMatchSteps` (4096), and
`maxPatternBranches` (1024). Unknown fields, including targets, reference
answers and executable evidence, are rejected.

## Typed and learned execution

For a system, replace `source` with a `representation` object using schema
`regelsuche.matrix-preparation-request/v1`, `equations` and ordered `unknowns`.
The adapter owns the inner profile and work budget: DIRECT selects
`RECOGNITION_ONLY_V1`, SAFE selects `SAFE_PREPARED_REPRESENTATION_V1`.
Supplying a conflicting inner profile or a separate inner work limit fails.
The artifact retains the complete canonical MatrixPreparation artifact,
including each RepresentationBridge.Relation, formation and concrete staged
replay, and downstream RREF when available. It is never treated as scalar
expression equality.

Learned authorities are optional trusted deployment configuration, via Java
property `regelsuche.runtime.learnedManifest`. The versioned manifest is:

```json
{
  "schema": "regelsuche.learned-runtime-authority-manifest/v1",
  "repositoryRevision": "0123456789abcdef0123456789abcdef01234567",
  "patterns": [{"id": "leaf", "root": "retained/leaf"}],
  "programs": [{"id": "learned-program", "root": "retained/program", "leafAuthorityIds": ["leaf"]}]
}
```

Roots use the existing retained-authority filenames. Pattern roots contain
`genome.json`, `authorization-bundle.json`, `split-manifest.json`,
`validation-selection.json`, `final-test-evaluation.json`,
`counterexample-evidence.json` and `authorization-receipt.json`.
Program roots contain `genome.json`, `program-plan.json`,
`program-replay-evidence.json` and `program-authorization-receipt.json`.
These are previously issued artifacts;
loading them calls the existing stored authorization replay contracts and does
not launch a new FINAL-TEST search. Every analysis and import reloads them and
checks revision, expiry, leaf/schema bindings and program topology. Requests
cannot supply authority paths. Exact promoted rule objects and original
program engines are retained; programs are not converted to pattern rules.

## Evidence and work identity

`regelsuche.runtime-class-content/v1` hashes sorted class names and class bytes
from the actual loaded application, authority modules and rule code sources.
It works without a Git checkout. Equivalent class directories and release
JARs have the same identity; JAR timestamps, absolute installation paths and
non-class packaging files do not affect it. Different compiled bytes,
including compiler/debug changes in a source-identical rebuild, intentionally
reject old artifacts. Conflicting class definitions also fail closed. The
V3 revision slot contains the first 160 digest bits and is explicitly labeled
as a runtime implementation digest, not a claimed Git commit.

The product work contract is `regelsuche.safe-product-logical-work/v1`.
It separates setup, analysis and verification. It retains the V3 occurrence
and delegated V2 receipts and charges their existing mechanical counters,
candidate primitive/theory execution work and independent replay. Original
measured program metrics include discarded internal work. Direct executors
without internal counters use an explicit conservative reservation of one
batch plus three calls per original parsed source node. Canonical simplification
does not reduce either this reservation or the source-node limit. Independent
direct primitive replay uses the same reservation. Learned setup charges one
complete revalidation batch per configured pattern or program, under its own
versioned convention.

V4 additionally retains its complete V3 base evaluation, the local occurrence
batch receipts and the lifted execution evidence. The global contextual budget
uses the remaining analysis allowance after runtime setup; its occurrence-batch
limit is the request's `maxVisitedStates`. Each occurrence evaluates all remaining
principals in one shared bounded traversal. Spent base, traversal, reconstruction
and replay work remains visible even when the next batch is refused. Verification
reserves the fresh analysis plus the additional independent concrete replay.
The contextual receipt uses `regelsuche.contextual-occurrence-work/v2` and
charges every principal replay, including the replay used to construct lifted
evidence. A traversal that throws before returning its counters retains a
bounded conservative reservation; a failed lift retains its replay reservation.
These charges appear in `failedBatchReservationUnits` and
`failedLiftReservationUnits`. Successful traversals retain their completed
mechanical receipts. A later unsupported occurrence cannot erase an earlier
budget-inconclusive occurrence for the same principal.
Context lifting accepts verified primitive paths; local theory provenance that
cannot be lifted under its existing authority is unsupported. Original authorized
theory and learned program executors retain their existing direct path.

These are logical units, not CPU timing. `maxWorkUnits` controls retention and
admission between bounded batches; an already started bounded authority batch
may cross it. Spent work remains charged, verification and candidate retention
are refused when their reservation no longer fits, and the result records
`BUDGET_INCONCLUSIVE`, including when every observed principal outcome is
negative and verification cannot run. Typed construction and its complete
replay each receive half the remaining budget. `maxPrimitiveRewrites` and `maxTheoryWorkUnits`
also constrain each retained scalar candidate. Technical failures, unsupported
applicability and no match retain separate outcomes. A batch may retain valid
earlier candidates while later work is inconclusive; inspect individual
outcomes as well as the summary status.

Every scalar candidate carries its original complete RecordedExecution,
including primitive/theory provenance and canonical work. Import recreates
the request using the current trusted runtime and compares the complete
artifact. A matching hash or imported PASS field does not authorize anything.

Current-head Gradle, full Maven/Product/Docker, SymPy, JMH and checkout-owned
ciCheck are integration gates. A fresh matched-work final product decision is
a separate follow-up; this adapter does not remove that requirement.
