# Choosing whether typed ranking pays for itself

## Purpose

Continue the typed dispatch integration with a cost-sensitive choice between the
existing inventory order and history ranking. A zero-weight history policy still
inspects the AST and can cost more than useful search. The selector must be able
to choose the actual inventory-order policy from TRAIN evidence.

## Design

Extend `TypedPolicySelection.Profile` with an explicit policy kind and an
`inventoryOrder(id)` factory. Preserve the existing two-argument ranked-profile
constructor. Inventory profiles carry zero weights and reject conflicting
weights. Execution selects the declared policy kind; ordinary history ranking
and its measured feature costs remain unchanged. Export the policy kind in a
versioned v2 frozen artifact. Selection still maximizes solved TRAIN tasks, then
minimizes all charged search work, then uses declared order.

Add a public development comparison using the actual `TraceRewriteStrategyLearner`
and its typed inventory. Derive TRAIN targets only from that learner's selected
TRAIN endpoints. Collect separate primitive and learned histories and train both
policy selectors against the same declared profiles. Keep every formation,
history-search, history-update and profile-trial work component visible.

Compare inventory, fixed default ranking and TRAIN-selected ranking for both
primitive-only and learned inventories. Every configuration receives identical
source, target, primitive-depth, search-depth, state and total-work bounds. Fixed
development cases cover one/two/three independent cancellation sites, an
uncancellable residual, and a simple zero removal. Budgets are 50, 150, 500 and
2,000 charged units. Retain every row, including losses and atomic overruns, and
all encoded search events and replay receipts. Freeze protocol and models before
evaluation and verify they remain unchanged afterwards.

## Boundaries

These are public development cases selected after an exploratory probe. They are
not independent family holdouts or a preregistered final test. No profile may be
selected using their results. Charged mechanics include feature inspection and
replay; compilation, serialization and complete CPU/bit costs remain outside this
ledger. The resulting work differences are not wall-clock speedups. Historical
frozen studies and production admission remain unchanged.

## Verification

Regression tests must reject an implementation that disguises a zero-weight
ranker as inventory order. A complementary branching case must still select
ranking when its savings exceed feature costs. The development comparison must
retain budget failures, replay every successful edge and charge learning
separately. Run affected Maven and Gradle suites and the repository CI before
merge.

## Observed development results (2026-09-19)

Both selectors chose actual inventory order from TRAIN. Fixed history ranking
was more expensive on these TRAIN tasks; the branching regression separately
demonstrates that ranking can still win when it avoids enough search.

At the common 2,000-unit budget, the full application-work ledger gives:

| Development case | Primitive, TRAIN-selected | Learned, fixed default ranking | Learned, TRAIN-selected | Outcome |
| --- | ---: | ---: | ---: | --- |
| One cancellation site | 78 | 72 | 40 | All reach target |
| Two independent sites | 163 | 221 | 117 | All reach target |
| Three independent sites | 256 | 464 | 248 | All reach target |
| Noncancelling residual | 59 | 138 | 74 | All inconclusive |
| Simple zero removal | 20 | 39 | 29 | All reach target |

The learned inventory retains an overhead on the two controls. The ranking-cost
choice does not learn to disable the learned inventory itself. At budget 50,
TRAIN-selected primitives solve one of five cases and TRAIN-selected learned
dispatch solves two; at budget 150 they solve two and three respectively. At
500 and 2,000 they both solve four. All 120 configuration/budget/case rows remain
in [summary.json](typed-policy-cost-choice-results/summary.json), including 52
unsuccessful rows and 32 atomic overruns. No over-budget row is a success.

One-time work is reported separately:

| Component | Primitive policy | Learned policy |
| --- | ---: | ---: |
| Typed history searches | 223 | 190 |
| History feature inspection and updates | 240 | 106 |
| All three profile-selection trials | 1,065 | 782 |

The target-free trace formation costs a further 2,734 charged units and supplies
the common TRAIN endpoints as well as the learned programs. It is retained once
as a separate shared formation component. No amortized end-to-end or CPU payback
is inferred from the per-application table.

## Reproduction and retained evidence

```sh
./gradlew :regelsuche-learning:typedLearningWorkStudy
```

The task writes a content-addressed directory under
`regelsuche-learning/build/reports/typed-learning-work/`. Independent Maven-built
and fresh Gradle-built Java 25 executions reproduced all 132 files byte for byte.
Their manifest records hashes for 131 payload files, including both frozen policy
artifacts, the complete formation record, eight typed TRAIN histories and all
120 evaluation searches with rejected events and primitive replay receipts.

The complete artifact set is retained as
[artifacts.json.gz](typed-policy-cost-choice-results/artifacts.json.gz). It is a
deterministic gzip of a JSON object whose `files` map contains the original
filenames and exact UTF-8 contents, including `manifest.json`. The plain
[protocol.json](typed-policy-cost-choice-results/protocol.json) describes the
declared cases, bounds, information regime and profiles. These artifacts carry
the development-only claim boundary above.

Protocol hash: `49d467d2bae2ef65a6fbd7cdc4509a03fa256ccab7af3079d4ce919310f2f94f`.
Frozen model hash: `53c973ef4bba1e625461127400fa08790ee953281031560a9e664b4b48b94afd`.

## Final local qualification

### Hosted review accounting clarification

The complete hosted verification for `98ce451` passed, including the final
`Checkout-local ciCheck` (run `35461345286`). Review identified an incorrect
receipt on descriptor-assumption rejection: no engine executes on this path, so
the typed compiled provider now records one requirement evaluation and one
rejection, matching `EngineMoveProvider`. A regression first failed on the
phantom engine/source invocations and then passed with the corrected receipt.

The suggested removal of primitive formation work is not applied. This ledger
uses the existing `LearnedSchedulingModel.trainingWorkComponents()` convention:
mechanical candidate events and primitive mathematical applications are distinct
dimensions, as defined by `TransformationWorkMetrics.totalWorkUnitsV2()` and
`ExecutionWork`. The legacy learner exposes application receipts separately.
Removing them would mix a mechanical-only discovery subtotal with the v2
reference and search totals. Formation remains 2,734 units; this is not CPU time.

The review overview also mentions incremental native scheduling. The fixed
study uses STAGED scheduling; native scheduling requires native providers and
inventory order, and does not support history ranking by design. This PR does
not add an incremental typed-provider implementation or weaken that validation.

After the review fix, a clean affected Maven reactor passed 2,495 tests in 431
suites. Fresh Gradle search and learning runs passed 407 and 865 tests respectively,
with no failures, errors or skipped tests. Independent Java 25 executions again
matched all 132 retained artifact files byte for byte, including the unchanged
2,734-unit formation total. Commands:

```sh
mvn -pl regelsuche-learning -am clean test
./gradlew :regelsuche-search:clean :regelsuche-learning:clean \
  :regelsuche-search:test :regelsuche-learning:test \
  :regelsuche-learning:typedLearningWorkStudy \
  --no-build-cache --no-configuration-cache -Dorg.gradle.vfs.watch=false
```

The Maven clean rebuild was necessary: an earlier incremental run retained a
class from the rejected mechanical-only hypothesis. That run is not the final
qualification. Full hosted CI must run again for the review-fix commit.

### Original cost-choice qualification

The complete affected Maven reactor passed 2,494 tests in 431 suites. A fresh
Gradle learning build passed 865 tests in 169 suites and executed the study task.
Both runs used Java 25, with zero failures, errors or skipped tests. Five new
regressions cover the cost-choice follow-up. An independent read-only review
found no actionable defects and verified every retained manifest entry,
unsuccessful row and atomic overrun. Full current-head repository CI remains the
merge gate; these local results do not replace its product, quality or JMH checks.
