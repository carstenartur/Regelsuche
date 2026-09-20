# Work replacement evaluation v3

P01 adds a separately versioned lifecycle account and comparison contract around
existing Regelsuche services. It does not add a frontier, learner, theorem checker,
program interpreter, or mathematical optimizer. The historical B0 execution and
raw v1/v2 artifacts remain available unchanged.

## Bound experiment

`WorkReplacementManifest` binds the full semantic baseline commit, historical and
improved execution revisions, rule/model/checker revisions, information regime,
objective definition, quality mode, continuation contract, seeds, resource limits,
ordered partitions, observation mode, lifecycle profile, and unsolved-task policy.
The development baseline is `7aec9ae0a1619dda98f859d1277ac8b287471423`.

The manifest distinguishes sufficient quality with minimum work from best quality
under a fixed budget. The typed adapter calls the existing online quality search
for the first contract and the existing full-continuation search for the second.
A score is not a target expression or proof authority. The same Query objects,
objective and checker are passed to every arm. Process workers reconstruct the
same typed source, registered objective and checker instead of serializing arbitrary
callbacks. A source identity is checked against its typed expression. Evaluation
queries in both VALIDATION and FINAL_TEST must use FROZEN_EVALUATION contexts;
all inputs are validated before any acquisition or session callback runs.

| Arm | Execution | Knowledge | Permitted comparison |
|---|---|---|---|
| B0 | historical full continuation | primitive | B0 versus B1: execution changes |
| B1 | improved shared adapter | primitive | B1 versus L1: additional learning |
| L1 | improved shared adapter | frozen selected knowledge | learning comparison, with actual witness IDs retained |
| L-oracle | improved shared adapter | diagnostic supplied application | diagnostic rows only |

Bindings are validated by role, including observation mode. Swapping a model and
checker revision is rejected. Oracle rows remain in artifacts but success summaries
and runtime ratios reject the oracle arm. Quality success does not itself prove
that learned knowledge was used: retained learned witness IDs supply that evidence.
Callbacks are trusted execution integrations, not a sandbox for untrusted code;
a manifest string alone cannot prove the implementation of an arbitrary objective.

## Eight phases and delegated work

`LifecycleWorkAccount` uses `regelsuche.lifecycle-work/v3` and records all eight
phases separately:

1. training search;
2. rule formation and proof;
3. selection training;
4. compilation;
5. restore and reproof;
6. query;
7. final check;
8. output.

Acquisition is the first two phases, not a replacement phase that merges them.
Every receipt has a unique ID, exclusive work, inclusive work, child IDs, raw
revision, and unchanged raw receipt text. A receipt forest must own every receipt
exactly once. Missing or repeated children, cycles, unowned receipts, duplicate IDs,
and inclusive/exclusive sum discrepancies fail validation. Only exclusive work is
summed by phase. `Math.addExact` makes work overflow an explicit failure. Zero work
requires an explicit explanation; negative work is rejected.

Each evaluation retains the selected structural output identity and exact input/output
scores. The typed adapter retains the original search JSON, then records the original
search plus incumbent-selection work under Query and independent selected-path
replay under FinalCheck. A successful online quality outcome may still be over the
budget after replay or output. A rejected final replay now exposes its completed
query, attempted replay work and rejected verification receipt through
`TypedSourceOnlySearch.FinalCheckFailure`; it is never a successful result.

The quality-selection v2 aggregate uses an exact JSON integer for `outputCost`.
Two `Long.MAX_VALUE` objective scores therefore export as
`18446744073709551614`. Historical v1 JSON and representable v2 numeric fields remain unchanged. Work aggregates retain checked `long` arithmetic.

## Lifecycle profiles and total-budget allocation

| Profile | Acquisition | Restore/session behavior |
|---|---|---|
| Fresh process per query | charged once for the experiment | requires a live distinct child process per query; closes each session |
| Loaded stream | runs and is charged once per arm | opens/restores one fresh session per arm and reuses it across the ordered stream |
| Provisioned model | previously incurred acquisition receipts required and included | does not rerun acquisition; opens/restores one fresh serving session per arm |

Primitive controls compile their primitive inventory and skip learned-model restore;
only learned arms pay learned-model reproof. The orchestrator requires new session
objects across arms and streams. The fresh
profile additionally checks live `Process` instances and distinct PIDs, rejecting
an in-process substitute. A bounded registered child-JVM integration fixture
exercises real schema restore and typed search; this is not general callback
serialization or a general persisted strategy format.

Every arm has the same total work limit G. Acquisition is deducted first, restore
is deducted before allocating query work, and `REMAINING_EQUAL_SHARE_WITH_CARRY`
divides the remaining balance by the remaining number of ordered queries. Unused
shares carry forward. Thus B1 can use the work it did not spend on learning for
queries. Charges may exceed a limit because operations and final checks are
indivisible; the actual overrun is retained. Exhausted work produces explicit
NOT_RUN rows for all remaining queries. No result matrix may omit or reorder a row.

## Measurement and failure scope

The logical account combines explicitly labeled observed units, not CPU
instructions: existing search/checker work, compiled provider instance counts,
materialized UTF-8 output bytes, and attempted UTF-16 stream writes. Final-check
witness text (including rejected verification JSON), query-component receipt text,
selected output identity, and genome artifacts all contribute their materialized
UTF-8 bytes. Shared formation JSON is materialized and charged once even when two
phase receipts reference it. Compilation
counts do not measure compiler internals or CPU bit complexity. Raw legacy receipts
keep their original meaning. These measurements support bounded logical-work
comparisons only; they do not prove a universal speedup or a complete CPU cost.

`WorkReplacementLearning` runs the existing trace learner, checked-schema formation,
source-only selector, and schema restore. TRAIN identities are checked before
acquisition and selection. Source overlap and backward provenance across
TRAIN/VALIDATION/FINAL_TEST are rejected. A sealed family holdout additionally
rejects a shared family across partitions. The only supported information-regime
strings are `PUBLIC_DEVELOPMENT` and `SEALED_FAMILY_HOLDOUT`; unknown spellings
are rejected. Public development permits distinct instances of a shared family,
without claiming a sealed family holdout. Public development examples remain
public development, even when their role in an individual run is FINAL_TEST.

Elapsed lifecycle time is measured directly around each arm and query; it is not
computed from CPU sums. Both controls bind the same PROFILE or QUIET_TIMING mode.
The typed adapter materializes its required receipt output in both modes. Optional
diagnostic streaming uses `WorkReplacementArtifacts.stream`, which records attempted
writes even when a sink fails; callers append those receipts to the active Journal.
Exporting a report later is a separate operation and its stream receipt must be
retained separately, avoiding a self-referential report size/work definition.

The in-process deadline is an observed deadline: an over-time returned execution is
TIMEOUT and cannot succeed. Bounded work/state limits still apply. Hard preemption
requires a process-backed session; the child-JVM integration uses the manifest's
`queryTimeoutNanos` for its query response read and terminates the child on timeout.
Its separate startup/restore response deadline remains 45 seconds; startup failure
prevents query execution and retains NOT_RUN rows. A typed query-timeout failure
produces TIMEOUT, retains known receipts, marks accounting incomplete for killed
work that cannot be observed, and leaves the remaining stream rows NOT_RUN. Other
exceptions remain ERROR. An arbitrary hanging in-process callback is not claimed
to be preemptible.

Errors, invalid proofs, unreachable thresholds, over-budget results and unrun rows
remain present. Integrations append completed receipts before throwing. If a legacy
operation throws without exposing consumed work, the Journal is explicitly
incomplete; known receipts are retained and no lifecycle success or ratio is
allowed. This is an honest measurement limitation, not an assertion that failed
work was free. P01 does not retrofit internal error ledgers into every old learner
or checker.

Runtime ratios require the manifest to declare `PENALIZE_AT_TIMEOUT`; all scheduled
rows contribute, with unsolved rows charged at least the declared timeout. There
is no successful-intersection shortcut. Incomplete accounting and provisioned
models without measured acquisition time cannot produce lifecycle runtime ratios.

## Reproducible verification

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests '*WorkReplacementAccountingTest' --tests '*WorkReplacementManifestTest' \
  --tests '*WorkReplacementExperimentTest' --tests '*WorkReplacementLifecycleIntegrationTest' \
  --tests '*TypedSourcePolicyQualitySelectionTest' --tests '*WorkReplacementProcessTimeoutTest'
```

The integration exercises actual training, proof formation, source-only policy
selection and reproof across all three profiles, including eight independent child
JVMs for four arms and two queries. Tests also compare deterministic mathematical
outputs and logical receipts across repeated controls; elapsed times are deliberately
not required to match. The tests characterize this bounded development integration;
no amortization, independent family capability, factor-two, or factor-ten result is
claimed.

The lifecycle integration supplies the oracle with one applicable learned schema
for two declared cancellation queries, restricts generation to that schema, and
checks its identifier in each selected final-check witness, including child JVMs.
Diagnostic binding/matching and output are paid separately from L1's empirical
selection. Both arms retain the same query/checker contract; oracle successes and
ratios remain excluded from learning summaries.
