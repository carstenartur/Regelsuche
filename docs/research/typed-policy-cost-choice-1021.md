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
