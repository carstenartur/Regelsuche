# Recurrence knowledge: complete process lifecycle evidence

## Intent and scope

Follow the recurrence-family integration with measured evidence for the existing
learner. Determine whether the positive logical-work result also amortizes real
elapsed time and CPU, and report process peak memory without overstating it.
This is the next measurement step authorized by the continuing project request.
Routine implementation choices below are the implementer's decisions.

Use native Java 25 and the unchanged public `CumulativeTaskKnowledge` APIs.
No new solver, learner, first-law shortcut, mathematical formula dispatch or
workflow threshold changes. Preserve the historical learned-schema-v2 protocol,
runner, frozen results and negative findings byte-for-byte.

## Approaches

1. Extend expression-specific Java experiment abstractions: broader changes to
   task types, yet no existing complete process CPU/RSS boundary.
2. Reuse the existing Python `AccountedSession` and runtime snapshot helpers with
   a thin JSONL adapter to public recurrence APIs: selected because it measures
   existing production behavior with a small independent boundary.
3. Time the existing JUnit main: rejected because assertions and unrelated test
   setup would contaminate the intended lifecycle.

## Fixed experiment

New `config/benchmarks/recurrence-knowledge-lifecycle-v1.json`, regime
`PUBLIC_DEVELOPMENT`, profiles `BASELINE`, `EXACT_FROZEN`, `FAMILY`, lengths
`[1,8,64]`, repetitions `3`, rotating profile order. No warmups. Actual prefixes,
not scaled timings: 27 lifecycles and 657 query requests.

Use the same 64 requests and order as `RecurrenceFamilyKnowledgeTest`:
eight rounds, each with parameters `4,7,-3,0,11,-7,1/2` then a negative control.
The two-dimensional matrix is `[[0,1],[1,p]]`, zero offset, initial state
`[round+2,2*round+3]`, degree 2. Negative control: matrix `[[2]]`, zero offset,
initial `[round+1]`, degree 2. Query budget 100000.
Two acquisition blocks: p=1 then p=2, training initial `[0,1]`, validation `[2,3]`.
Family validation p=3 initial `[2,3]`, p=5 initial `[3,4]`. Acquisition budget
500000. All requests are explicit JSON with canonical rational strings.
Setup timeout 120s, query timeout 30s, Java options `-Xmx512m`.

BASELINE starts one fresh query JVM and initializes empty knowledge. Learned
profiles start a producer, actually learn, optionally generalize, save, and
close it; a separate JVM loads/reproves the snapshot before the query stream.
The baseline pays only its own preparation. All actual failures and unexecuted
planned rows remain in evidence. No query-side learning or cache.

## Shared JSONL contract

Worker: `de.regelsuche.evolution.RecurrenceKnowledgeComparisonWorker`, public
`Map<String,Object> handle(String line) throws IOException`, static main.
Print and flush `READY` on startup. JSON revision
`regelsuche.recurrence-knowledge-worker/v1`. Commands use field `command`.

| Command | Additional fields | Success status |
| --- | --- | --- |
| initialize | profile | INITIALIZED |
| learn | training, validation, budget | LEARNED |
| generalize | validation, budget | GENERALIZED |
| save | directory | SAVED |
| restore | profile, snapshot | RESTORED |
| run | id, task | RESULT |

Task fields are exactly `linear`, `offset`, `initial`, `degree`, `workBudget`.
Training/validation arrays contain tasks without IDs. The driver supplies run
IDs separately. Reject unknown/duplicate fields and invalid state transitions.
Initialize/restore happens once. BASELINE is query-only. Learned initialize
creates a producer; only FAMILY may generalize. Save terminates producer
operations; restored consumers only run. Technical failure poisons the session.

All successful responses include revision, status, profile, generationId,
acquisitionWork, acquisitionCosts, sessionSetupWork, recurrenceCount and
recurrenceFamilyCount. Costs map keys: lessons, formation, preparation,
validation, selection, restoration, splitChecking, total.
Learn/generalize additionally return reason, promoted, work and training
receipts (outcome, solved, reused, totalWork); rejected attempts are valid paid
outcomes. Save/restore return snapshot and snapshotHash (`sha256:` prefix).
Run returns id, exact task, outcome, solved, reused, totalWork, law (null when
unsolved; otherwise polynomial, evolution, initialValue). Data comes from the
real Execution/VerifiedLaw, never expected answers. ERROR has error and
accountingComplete=false, knownWork/completedLessons when LearningFailure
provides them. Optional method metrics are unnecessary for whole-process data.

## Measurement and independent validity

Reuse `AccountedSession`, `snapshot_runtime`, `verify_runtime`,
`source_provenance`; do not change their historical module. Retain receipts even
when READY construction fails (new local subclass with receipt sink).
Sequence wall time spans first process start through last shutdown, including
controller IO and exact external law audit. Worker CPU sums wait4 user+system
for every owned JVM, all threads including startup/shutdown. RSS is maximum
worker peak RSS, never the sum or an intrinsic heap/algorithm working set.

An exact Python auditor uses the existing sparse rational polynomial parser,
simultaneous affine monomial substitution, and checks `P(Ax+b)=s*P(x)` and the
reported initial value. Require nonconstant law, bound variables/degree, correct
request/ID/generation/profile/budget and coherent outcome flags. No Java success
flag alone establishes validity. Quality equality is the complete audited
success vector, allowing distinct valid laws. No ratios for technical failures,
missing operations/receipts/rows or unequal quality. Negative controls stay in
denominators. Logical costs, worker CPU and controller CPU remain distinct.
For this fixed known stream, qualified comparisons additionally require the
expected solved counts 1/1, 7/8 and 56/64. The family arm must reuse the solved
requests; the exact-only arm must reuse none. Independent proof covers positive
laws; an unsolved receipt is checked for consistency, not a general no-law proof.

Snapshot exact ordered production runtime bytes, resources and dependencies;
bind inventory, clean source commit/tree, protocol hash, Java executable/release
hashes. Before qualified execution commit implementation/protocol. Normal
Maven-built production classes plus dependency classpath are acceptable; never
test classes or compatibility-bytecode copies. Command measurement uses an
outer supervisor around the child including report/manifest writing; final
supervisor receipt write is outside its own finite measurement boundary.

Outputs: protocol, metadata, runtime inventory/copy, raw operations/query rows,
process receipts and stderr, snapshots, sequence summaries, paired comparisons,
human report, recursive hashes manifest, command receipt. Output directory must
be new; never overwrite historical evidence. Separate verify checks manifests,
runtime/snapshots, schedule/operations, all audits and recomputed summaries.

## Limits and acceptance

This known developmental family is not a sealed holdout or a claim about all
recurrences/CAS systems. Baseline remains the existing complete bounded
synthesizer although the task requires one law. Report measured regressions as
plainly as improvements. Acceptance requires actual 27-lifecycle execution,
657 retained requests, independent verify, native focused tests, independent
review and the unchanged required CI gates before integration. Integrate #1135
first; open the next PR directly against its merged main successor.
