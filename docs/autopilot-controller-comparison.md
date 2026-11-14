# Preregistered controller comparison on the public production campaign

This protocol compares allocation decisions on the existing target-free #355 campaign. The production definition, two observation groups and their known historical outcomes predate this comparison. The panel is a retrospective control, and the control policies are fixed before their first measured execution.

## Inputs and policies

Start each lane from the unmodified `PinnedAutonomousProductionCampaign` brief, seed catalog, default rule inventory, search policy, initial empty evidence and deterministic seed. Run the same generator, search, miner, validation, counterexample search, novelty check, proof and lifecycle services. Keep all seven stage budgets and their resource units. Search states, candidate slots and proof attempts have different units. Only the candidate slots are divided between the two eligible, predeclared formation batches. Each batch gets one mining dispatch.

| Lane | Full observation batch / predeclared pair | Decision after the round |
| --- | --- | --- |
| `PRODUCTION_FEEDBACK` | Existing allocation: reserve one candidate slot for the pair, assign the remainder to the full batch. | Invoke the existing production feedback controller on retained receipts. |
| `FIXED_UNIFORM` | Divide candidate capacity equally, with any odd extra slot assigned to the full batch in fixed order. | Stop at the preregistered one-round horizon. |
| `NO_FEEDBACK` | Same allocation as production. | Stop at the preregistered one-round horizon without reading execution receipts to select the next action. |

Run each lane independently. Never fund another lane from unused resources. Publish every lane, including a failed lane, and both branches' input identities, counts, candidate/rejection records and actual allocations when returned. Distinguish complete, partial and unavailable evidence. Preserve the original configured/executed/skipped/remaining ledger in physical units, branch diversity, lifecycle disposition and the separate qualification gates. The fixed policies may read output for evaluation after schedule selection.

Compare branch outcomes, candidate identity, completion and each resource dimension separately. A difference in allocated capacity alone establishes a scheduling difference. Identical outcomes and work are a null comparison on this campaign. The existing production controller's only post-receipt decision on this panel is terminal completion. This panel cannot measure which of several eligible continuations an adaptive controller would select, or the effects of redistributing resource units across stages. Repeat the complete matrix in separate processes and compare canonical results; runtime and host paths are diagnostic only.
