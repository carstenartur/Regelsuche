# Checked learned polynomial schemas

`CheckedLearnedSchemaModel` adds direct, independently verified applications to
the existing typed frontier. `TraceRewriteStrategyLearner` remains the producer
of training searches. The schema model performs bounded hypothesis formation and
symbolic checking; it introduces no second search engine or target oracle.

## Formation and lifecycle

1. Train the existing learner and retain its `FrozenStrategy`. Formation reads
   actual selected paths with an admitted multistep trace and verified observed
   replay. It does not accept a supplied desired schema.
2. Extract selected source/target endpoints and contiguous path windows of at
   least two steps. Strip equal outer binary operations only when the unchanged
   sibling is structurally identical. No algebraic normalization chooses the
   changed subtree.
3. Pair compatible source shapes from distinct training observations and call
   `TypedPatternGeneralizer`. Its shared subtree-vector bindings span both sides
   of both observations. Repeated variables retain the same placeholder, including
   compound expressions and scoped symbol identities.
4. Check each candidate over distinct symbolic indeterminates using
   `ExactPolynomialAnalysis.requireEquivalent`, which delegates to the existing
   bounded exact residual polynomial arithmetic. Agreement on the concrete
   training substitutions is insufficient. Only proved statements create
   privately constructed immutable `Schema` objects.
5. Create indexed typed providers and use the existing `TypedMoveSearch` or
   `TypedSourceOnlySearch`. Persist with `toCanonicalJson`; restore with `load`
   and the independently supplied expected inventory hash. Restoring needs no
   live `FrozenStrategy` and re-proves every admitted statement.

The public development fixture currently forms three schemas from its actual
observations: the full cancellation to a square, a cancellation suffix, and a
partial continuation. Which of these pays for its use is a separate source-only
selection question. These development cases are not independent transfer data.

## API

The class is in `de.regelsuche.evolution` in `regelsuche-learning`.

| API | Contract |
| --- | --- |
| `learn(FrozenStrategy)` | Forms a model under `Bounds.defaults()` from existing observations. |
| `learn(FrozenStrategy, Bounds)` | Allows tighter bounds; bounds cannot exceed the supported maxima. |
| `load(String json, String expectedInventoryHash)` | Validates canonical structure, revisions, domain, bounds and inventory binding, then re-proves all statements before checking their descriptive proof identities. |
| `schemas()`, `attempts()` | Immutable admitted statements and retained formation outcomes. |
| `providers()` | Returns one indexed typed provider, or an empty list when there is no admitted schema. |
| `providers(int maximumSchemasPerOccurrence)` | Limits the schemas attempted at each structurally relevant occurrence. |
| `providers(int, Map<String, Double>)` | Orders relevant schemas by finite supplied utility, then structural size reduction, then schema ID. Utility is scheduling evidence, never proof authority. |
| `providers(int, Map<String, Double>, Set<String>)` | Indexes only the explicit registered schema subset; unknown IDs are rejected. An empty subset yields no provider. |
| `verifier()` | Verifies a concrete registered application without primitive-path regeneration or discovery at other occurrences. |
| `requiring(List<String>)` | Returns another immutable model with additional normalized caller prerequisites; prerequisites are checked during both generation and verification. |
| `inventoryHash()`, `inventorySemanticsHash()` | Expose the genome binding and its versioned semantic binding. |
| `formationWork()`, `loadWork()` | Expose additional deterministic formation work and fresh load/recheck work separately. |
| `toCanonicalJson()` | Returns the immutable canonical model artifact. |

`TypedLearnedMoveInventory.checkedSchemas()` is a convenience formation bridge.
`newSchemaSearchSession(model, ...)` combines direct schemas with the same
primitive providers and dispatches to the appropriate verifier. Existing compiled
trace programs remain separately available; the schema model itself does not
emit trace fallback applications.

## Supported domain and trust boundary

The theorem domain is the commutative scalar rational polynomial algebra.
Expressions may contain exact rational literals, scalar variables, addition,
subtraction, multiplication, division by a nonzero literal, and nonnegative
literal integer powers. The declared polynomial convention gives every zero
power the value one. Functions, symbolic denominators and symbolic or negative
exponents are excluded. The whole concrete source and instantiated target must
satisfy the domain; an unsupported enclosing function is excluded too.

The initial theorem domain is unconditional. A conditional identity such as
`x/x = 1` cannot acquire authority by omitting its nonzero premise. Generalized
schema assumptions must be empty, including on load. `requiring(...)` adds caller
gates to otherwise unconditional theorems; it does not upgrade the checker to
prove conditional mathematics. Missing caller prerequisites reject both proposal
generation and verification. `ExactTheoryEvidence` therefore retains its existing
unconditional contract.

The artifact carries the model revision, checker revision, explicit arithmetic
profile, domain, bounds, inventory hash and inventory semantics hash. The
inventory binding includes the genome and typed transport revisions. Loading
rejects stale bindings and unsupported fields or limits. It checks the symbolic
statement before comparing stored theorem digests. Origin strategy hashes and
supporting observation IDs describe provenance; they do not independently prove
that a serialized history occurred, and they never authorize an identity.

`CheckedSchemaTheoryEvidenceProvider` is the installed core SPI bridge. It accepts
only the model's privately issued immutable application capability. Public JSON,
`ExactTheoryEvidence.Binding` records, hashes, scores and decoded observations
cannot construct that capability. A direct schema application is represented as
one exact-theory step with zero primitive steps; it does not claim a fictitious
primitive macro expansion.

For an application, exact structural matching determines a consistent binding
map. `TreePosition` replaces the selected occurrence and preserves the rest of
the typed AST. Application evidence binds the full encoded source and target,
occurrence path, substitutions, schema and model identities, revisions and work.
The independent verifier navigates the supplied path, repeats exact matching,
checks the substitutions and domain, reconstructs the full target and compares
the bound evidence and registered move metadata. It neither enumerates primitive
traces nor treats a stored digest as sufficient evidence.

Search capability deltas belong to state assessment, not to the polynomial
identity. `MoveSearch` calculates them from its `StateValue` result and overwrites
provider annotations before retaining a witness. The schema verifier excludes
only this delta from its mathematical comparison. Consequently a legitimate
assessed capability survives independent witness replay, while a provider's
invented capability does not enter the assessed successor state.

## Negative outcomes and bounded selection

`attempts()` retains `PROVED` and `TRACE_ONLY` outcomes, plus explicit
`EXTRACTION_LIMIT` and `FORMATION_LIMIT` receipts when work remains unexamined.
Examples without an admitted multistep trace, hypotheses without a common
source-bound abstraction, unsupported domains and unsuccessful symbolic checks
are not promoted. Failed checks retain the work already observed. Unexamined
hypotheses are not classified as false.

Default structural limits are 512 expression occurrences, 128 pattern nodes,
depth 64, 256-bit rational literals and exponent 32. Formation retains at most
128 selected examples, checks at most 256 compatible pairs, and admits at most
32 schemas. A provider attempts at most 256 matches and emits at most 32
candidates in a batch. The symbolic checker also has its separately persisted
exact arithmetic limits, including polynomial degree, terms and products.

An immutable index selects schemas by root operator before exact matching. A
nonempty provider using only a subset of registered schemas reports an incomplete
relation. Per-occurrence suppression, matching or candidate exhaustion, and
unsupported target instantiation likewise prevent a completeness claim.
Reference evaluation must retain all registered schemas and alternatives; an
explicit budgeted profile may choose a subset or smaller per-occurrence cap.
Even the default provider does not claim completeness if a hard bound is reached.

## Proof and reuse costs

The logical counters are deterministic event receipts, not a complete count of
CPU instructions, allocation, hashing or arbitrary precision arithmetic cycles.
Wall time, process CPU, allocation and memory therefore remain separate measured
dimensions in the comparison worker.

| Cost | Accounting boundary |
| --- | --- |
| Existing learner training | Remains payable separately; `formationWork()` does not replace the learner's search, replay, exact audit or minimality work. |
| Schema formation | Counts extraction and pairing events, generalizer invocations, explicit structural visits and exact symbolic node/term work, including failed attempts. |
| Persistence and restoration | Artifact I/O and process setup belong to end-to-end measurements. `loadWork()` counts fresh decoding/checking events and symbolic reproof; serialized formation counts grant no authority. |
| Provider setup and selection | Building the chosen immutable index and paid TRAIN utility comparisons belong to setup/selection measurements. |
| Generation | Counts domain visits, occurrence/index visits, actual matcher steps/branches and the application work represented by emitted exact-theory edges. |
| Failed or unchanged application | Keeps the partial substitution and target-validation work in the delegated mechanical ledger because there is no emitted edge to carry it. |
| Independent verification | Pays fresh premise/domain checks, path navigation, matching, substitution checks and full-target reconstruction. Source-only selected-path replay is an additional paid verification. |

The immutable model's canonical hash is computed once during construction and
reused in application bindings. This preserves the exact bytes and identities
while avoiding repeated hashing of the complete model for each application. It
is not a new cache and does not reuse application authorization. Every receiving
verification still checks the concrete source, path, bindings, prerequisites and
target.

`CheckedLearnedSchemaModelTest` and `CheckedSchemaProofBoundaryTest` cover direct
formation and reuse, false repeated bindings, distinct symbolic indeterminates,
exact rational coefficients, scoped compound bindings, missing prerequisites,
conditional/unsupported domains, proof and target tampering, stale revisions,
inventory mismatches, bounded arithmetic and ASTs, selection subsets, rejected
work accounting, and the separation of mathematical evidence from assessed
capabilities. Passing these contracts is not a runtime speedup claim.

## Registered v2 evaluation — 2026-09-20

**The threefold performance goal is unmet.** Both learned profiles reached exactly the same independently verified output costs as BASE on every registered case, while every fully paid, quality-matched sequence comparison against BASE was slower in wall time and whole-worker CPU. No verified ability gain or equal-total-budget advantage was demonstrated. The strong fixed SymPy portfolio produced strictly cheaper outputs on six of eight cases and tied on two.

This was one complete measured invocation: four profiles × three actual sequence lengths (1, 8, 32) × three repetitions, totaling 36 sequences and 492 queries. These are eight distinct public family probes and controls, repeatedly executed; they are not 492 independent mathematical tasks or a blinded external test. Length 1 always contains `nested-invariant`; lengths 8 and 32 traverse the frozen case order once and four times, respectively. All attempts remain in the archive. Earlier provenance-gate failures started zero queries and supplied no performance results.

Independent `run_schema --verify` completed with exit 0 on the retained archive. It checked the recursive artifact/runtime manifest, full sequence matrix, model persistence/restore binding, source and output structure/cost binding, selected path continuity and polynomial identities, timing partitions, summaries and paired comparisons. All 369 Java queries received the independent selected-path audit, including 96 learned edges. All 63 owned worker processes exited normally through graceful EOF and yielded user/system CPU and RSS receipts.

| Profile | Complete sequences | Valid queries | Improved | Unchanged | Worse | Errors / timeouts / budget overruns |
|---|---:|---:|---:|---:|---:|---:|
| BASE | 9/9 | 123/123 | 78 | 45 | 0 | 0 / 0 / 0 |
| LEARNED_SCHEMA | 9/9 | 123/123 | 78 | 45 | 0 | 0 / 0 / 0 |
| LEARNED_SELECTED | 9/9 | 123/123 | 78 | 45 | 0 | 0 / 0 / 0 |
| SYMPY_PORTFOLIO | 9/9 | 123/123 | 93 | 30 | 0 | 0 / 0 / n/a |

There were also zero unsupported inputs and zero invalid identities. The largest observed Java query charge was 7,536 against the fixed 16,384 query cap. Training and restore work are additional paid work; equal query caps do not constitute an equal total-work budget.

Output quality below is the common unsimplified surface-operation count; lower is better. Each displayed value was identical across that case’s repetitions and sequence positions.

| Case / declared family | Input cost | BASE | LEARNED_SCHEMA | LEARNED_SELECTED | SYMPY_PORTFOLIO |
|---|---:|---:|---:|---:|---:|
| nested-invariant / nested quadratic substitution | 15 | 2 | 2 | 2 | 1 |
| quadratic-cross-substitution / nested quadratic substitution | 15 | 5 | 5 | 5 | 3 |
| determinant-residual / difference of quadratic invariants | 11 | 3 | 3 | 3 | 0 |
| geometric-telescope / geometric telescoping product | 8 | 7 | 7 | 7 | 2 |
| geometric-shift / geometric telescoping product | 5 | 5 | 5 | 5 | 2 |
| compound-near-miss / negative control | 10 | 9 | 9 | 9 | 6 |
| wrong-repeated-binding / negative control | 5 | 5 | 5 | 5 | 5 |
| simple / negative control | 1 | 1 | 1 | 1 | 1 |

For example, Java retained `(a ^ 2) ^ 2` versus SymPy’s `a**4`, and `a ^ 2 - a ^ 2` versus `0`. The geometric cases are inside the shared polynomial grammar; the learned model supplied no useful selected schema edge for them. Their poorer Java results cannot be excluded as unsupported coverage.

The following are medians of three separately executed complete lifecycles per cell. Wall time includes both Java startups, training/policy selection, exact model persistence, graceful training shutdown, fresh-process restore/recheck, every request and its independent audit, and final worker shutdown. SymPy pays one worker startup and its full fixed portfolio. These are measured sequences, not extrapolated single-query means.

| Length | Profile | Lifecycle wall (s) | Whole-worker CPU (s) | Sequence controller CPU (s) | Peak wait4 RSS (MiB) | Reported method allocations (MiB) | Total Java logical work |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | BASE | 2.446 | 5.494 | 0.032 | 151.0 | 60.7 | 4,537 |
| 1 | LEARNED_SCHEMA | 2.994 | 6.647 | 0.019 | 162.0 | 86.3 | 10,661 |
| 1 | LEARNED_SELECTED | 3.356 | 8.564 | 0.021 | 152.5 | 141.6 | 21,152 |
| 1 | SYMPY_PORTFOLIO | 0.743 | 0.737 | 0.002 | 131.7 | not measured | not comparable |
| 8 | BASE | 3.040 | 8.041 | 0.101 | 258.2 | 150.6 | 16,934 |
| 8 | LEARNED_SCHEMA | 3.732 | 9.891 | 0.061 | 243.9 | 168.1 | 26,934 |
| 8 | LEARNED_SELECTED | 4.429 | 11.931 | 0.060 | 247.5 | 232.7 | 33,549 |
| 8 | SYMPY_PORTFOLIO | 0.863 | 0.856 | 0.006 | 181.7 | not measured | not comparable |
| 32 | BASE | 4.957 | 12.810 | 0.265 | 328.8 | 519.1 | 67,736 |
| 32 | LEARNED_SCHEMA | 5.419 | 14.353 | 0.162 | 335.6 | 500.9 | 95,682 |
| 32 | LEARNED_SELECTED | 5.969 | 15.789 | 0.245 | 323.2 | 599.9 | 84,351 |
| 32 | SYMPY_PORTFOLIO | 1.518 | 1.488 | 0.020 | 191.8 | not measured | not comparable |

Whole-worker CPU is the sum of OS user and system CPU across all worker threads and lifecycle phases, including startup/shutdown; it can exceed elapsed wall time. Across all 63 workers the receipts total 257.629 user CPU seconds plus 36.898 system CPU seconds (294.527 seconds). Native allocations cover only the current Java worker thread during reported methods; they exclude unmeasured startup, other threads, transport serialization outside those methods and shutdown, and are neither total JVM allocations nor retained heap. SymPy allocation absence means unmeasured, not zero. Java logical units are not comparable with CAS instructions.

RSS is the maximum wait4 process-lifetime high-water receipt across a sequence’s workers, not an intrinsic solver working-set or model-size measurement. It depends strongly on run order: SymPy’s length-1 receipts were 47.0, 131.7 and 274.0 MiB, and later unrelated workers sometimes have identical peaks. An inherited controller high-water footprint around process launch is a possible explanation, not a cause established by this experiment. No native-memory superiority claim follows from this table.

**Experiment accounting remains separate.** The sum of the 36 recorded lifecycle wall intervals is 120.507 seconds; there is no recorded whole-experiment wall stopwatch to report. The execution-wide controller CPU counter is 33.689 seconds, of which 2.976 seconds falls inside sequence intervals. The remaining 30.714 seconds includes shared preparation, accounting, cumulative artifact serialization/checkpoint writes and other work outside the per-profile wall timers. Shared harness preparation is separately recorded as 0.368 wall seconds and 0.157 controller CPU seconds. None of this experiment overhead is free, but the retained data do not assign it to individual arms. No invented redistribution is applied. Final report construction and later offline verification also fall outside the execution-wide counter’s recorded endpoint. The comparisons below therefore concern the complete recorded algorithm lifecycles, not an unrecorded whole-command wall cost.

Paired speedup means comparator time divided by candidate time; a value above 1 favors the candidate. These are medians of the three per-repetition ratios, not ratios of the preceding median times. Every admitted pair has the same entire input sequence, identical independently verified output-cost vector, and the same configured query cap; all learning/load costs are included.

| Candidate / comparator | Length 1 wall / worker CPU | Length 8 wall / worker CPU | Length 32 wall / worker CPU |
|---|---:|---:|---:|
| LEARNED_SCHEMA / BASE | 0.841 / 0.863 | 0.861 / 0.868 | 0.883 / 0.893 |
| LEARNED_SELECTED / BASE | 0.648 / 0.575 | 0.766 / 0.723 | 0.829 / 0.821 |
| LEARNED_SCHEMA / LEARNED_SELECTED | 1.078 / 1.180 | 1.126 / 1.201 | 1.122 / 1.118 |

All 18 learned-versus-BASE wall ratios and all 18 worker-CPU ratios are below 1. LEARNED_SCHEMA wall ratios span 0.638–0.987; LEARNED_SELECTED spans 0.555–0.832. LEARNED_SCHEMA generally has lower lifecycle cost than LEARNED_SELECTED, whose additional policy training is paid; this provides no gain over BASE. No complete Java/SymPy sequence has equal output quality, so no quality-matched net-speed ratio versus SymPy is eligible. The two tied control cases cannot be detached from their sequence setup costs to manufacture one. Three repetitions provide descriptive evidence, not a broad population or statistical superiority claim.

The observed bottleneck is not a failure to apply a theorem. Three schemas were formed from 16 retained attempts (3 proved, 13 trace-only). LEARNED_SCHEMA selected 96 direct checked-theory edges, all using `(u+v)*(u-v)+v*v -> u^2`, schema `checked-schema:sha256:14cbff69223f06324f0f7155e7e6d6e4e8d54270f4ab24c81a04fe37929a0b4d`; these edges have no primitive expansion. Its selected witnesses use 126 total edges versus BASE’s 348, yet its search continues broadly and pays more generation, search and verification work. All three leading cases reach 48 explored states under LEARNED_SCHEMA. For one actual eight-query cycle the ledger is:

| Logical-work component | BASE / LEARNED_SELECTED | LEARNED_SCHEMA | Difference versus BASE |
|---|---:|---:|---:|
| Provider/generation ledger (`primitiveWork`) | 260 | 1,733 | +1,473 |
| Search work | 11,702 | 14,858 | +3,156 |
| Verification in search | 533 | 2,570 | +2,037 |
| Incumbent selection | 4,361 | 3,439 | -922 |
| Selected replay/application verification | 78 | 316 | +238 |
| All query work | 16,934 | 22,916 | +5,982 |

Thus LEARNED_SCHEMA spends 22,916 versus 16,934 query units (+35.3%) before its 3,436 training and 582 restore units. Extra provider/generation (+1,473), search (+3,156) and verification (+2,037) charges exceed the smaller incumbent-selection charge (−922). These are measured logical-work categories, not a microarchitectural CPU attribution. At length 32 its median native query-method wall total is 2.622 seconds versus BASE’s 2.445; median Python path-audit time is actually smaller (0.095 versus 0.171 seconds), so fewer audit edges did not produce an overall win. Model-file persistence/load medians are below 0.3 ms per phase and are not the major observed setup cost.

LEARNED_SELECTED chooses `BASE` with no included schemas in all nine independent training lifecycles. All six policy trial profiles achieve the same aggregate training output cost, 13, without budget violations; BASE costs 1,083 continuation units versus ALL/RANKED 2,770 and the three single-schema trials 1,875/1,883/2,216. The three single-schema utility estimates are −792, −800 and −1,133. Selection consumes 12,597 units on top of 3,436 formation units, giving 16,033 training plus 582 restore units per lifecycle. Its query work exactly equals BASE’s thereafter, leaving a paid 16,615-unit overhead at every registered length. This is correct negative utility selection, not a learning-efficiency improvement.

LEARNED_SELECTED has nine different serialized model hashes because selection evidence contains measured wall/CPU/allocation diagnostics. Removing only those measurement fields yields one identical model/policy description. Each sequence persisted and restored its own exact bytes/hash successfully. BASE and LEARNED_SCHEMA each have one stable model hash across their nine lifecycles.

The useful demonstrated foundations are checked schema formation, direct theory-step application, fresh-process persistence and semantic rechecking, conservative source-only policy selection, and independent structural/polynomial audits. Neither shorter witnesses nor these completed mechanisms satisfy the threefold end-to-end goal. No model, protocol, case, budget or threshold was tuned after observing these results.

The retained source/runtime identities are:

| Identity | Recorded value |
|---|---|
| Source revision | `c7bbe08b162fc23c75c0bf83f7de65174431b916` |
| Source tree | `git:1c8d8034c409f77795d3b4e9a0638a0b43d2914a` |
| Published protocol freeze | `5ce2980fe68eb78e352f262934cf338f002a72f4` |
| Original local freeze (descriptive) | `5bc9e21e61131be287b89060ef39f93df111dc25` |
| Protocol / historical protocol SHA256 | `7e9b51c8f4c66655489350ef4be982411b6ab5ae0585ddb8b725b1ef15d7113c` |
| Actual copied runtime bytes | `sha256:d25ac0a63b88979396a8c5139ae76e9688fa905297d812dee830bb0b21f0df7b` |
| Artifact manifest SHA256 | `abdd9975d42dace85ef71ced87ce2116dafeac599ba27abdcc9dd80f3602a3bb` |
| JDK executable SHA256 | `d421a502734dc104789424d68b147cbeeca4e9b6ea786ec50d48967e3389993e` |
| JDK release-file SHA256 | `10442d5ef7930f4220a3336d65f020b24812660824ea995a51ba29f278aed30b` |

Environment: Temurin OpenJDK 25.0.2+10, Python 3.12.14, SymPy 1.14.0, Linux x86_64. Source identity is distinct from actual runtime identity; all Java workers ran from the copied class/JAR inventory under `runtime/`. The exact commands and per-process receipts are retained in `metadata.json` and `sequences.json`. Primary evidence is `sequences.json`, `rows.json`, `paired-comparisons.json`, individual exact model files, `runtime-inventory.json`, and `manifest.json` in `build/reports/learned-schema-efficiency-v2-20260920`.

The analysis ran only the following independent verification command, with exit 0; its log is `/workspace/scratch/6b23a1484cae/schema-v2-agent-audit-verify.log`:

```bash
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts \
  /workspace/scratch/6b23a1484cae/schema-evaluation-venv/bin/python \
  -m external_polynomial_comparison.run_schema --verify \
  --output build/reports/learned-schema-efficiency-v2-20260920
```

Reproduction instructions, not an additional measurement performed by this analysis: use a clean checkout of the recorded source revision and JDK 25.0.2+10, rebuild the classpath, and choose a new output directory. The driver rejects overwriting an existing run, checks both historical/current protocol bytes and ancestry, and snapshots the exact runtime before any sequence.

```bash
export JAVA_HOME=/workspace/scratch/fcfe4cbdbc35/runtime/jdk-25.0.2+10
export PATH="$JAVA_HOME/bin:$PATH"
export PYTHONPATH="$PWD/scripts"
export PYTHONDONTWRITEBYTECODE=1
./gradlew --no-daemon --no-configuration-cache --no-build-cache --max-workers=2 \
  -I scripts/external-polynomial-classpath.gradle \
  :regelsuche-learning:externalPolynomialClasspath
python3 -m venv build/schema-v2-reproduction-venv
build/schema-v2-reproduction-venv/bin/python -m pip install sympy==1.14.0 mpmath==1.3.0
build/schema-v2-reproduction-venv/bin/python -m external_polynomial_comparison.run_schema \
  --classpath regelsuche-learning/build/external-polynomial-classpath.txt \
  --output build/reports/learned-schema-efficiency-v2-reproduction-UNIQUE
build/schema-v2-reproduction-venv/bin/python -m external_polynomial_comparison.run_schema \
  --verify --output build/reports/learned-schema-efficiency-v2-reproduction-UNIQUE
```

The unmodified metadata, protocol, summary, paired comparisons and inventories are
[retained in the repository](learned-schema-efficiency-v2-evidence/2026-09-20/).
The complete raw rows, exact model files, executable snapshot and recursive manifest
are delivered in `regelsuche-effizienz-v2-rohdaten.zip`, under
`learned-schema-efficiency-v2-20260920/`. The archive also preserves the earlier
uncached worker profile, modular execution diagnostics and the local freeze Git bundle.
The source revision above remains the measurement identity. After that run, the
complete quality lifecycle exposed a method-complexity hotspot in schema formation.
Observation collection was mechanically extracted into one private helper in
`CheckedLearnedSchemaModel`; the pairing, proof and work operations retain their
order. Nineteen focused model/worker/proof tests passed. Across eleven bound
configurations and three queries each, canonical model bytes, application evidence
and work receipts were byte-identical before/after (74987 bytes; SHA256
`bdc42f2dc4f1a38d2646e120f538fea689edfb119d3065c00006477f1c2ff691`).
This maintenance refactor changes bytecode, so the original timing table is not
relabeled as a measurement of the final refactored binary. Subsequent CI executions
are separate qualification runs; the primary negative archive is unchanged.
No rule, utility choice, protocol case, budget or quality threshold was tuned.
