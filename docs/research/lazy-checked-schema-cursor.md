# P03 — demand-driven checked-schema applications

This opt-in implementation follows integrated P02 at `f751f90f58ee10aa122c42218b66a13760053aa0`. It uses the existing learner, theorem restoration, private checked-application capability, registered incremental provider and typed search frontier. It adds no search or learning algorithm.

## Execution and proof boundary

`CheckedSchemaMatcherPlan.prepare(model, maximum, utilities, included)` validates and copies configuration, prepares an immutable root-shape index and reusable bounded matcher wrappers, and returns an explicit compilation receipt. This preparation is not a theorem proof or a cache authorization. The model must have been learned or independently restored through `CheckedLearnedSchemaModel`.

Each provider session maintains a preorder occurrence stack, the current indexed schema range, and at most one completed match or one already-paid application. A pull performs only the next bounded operation. The existing matcher is atomic within its declared bounds; it is not presented as a newly resumable matching engine. Completed matches and applications survive pull boundaries without being reconstructed. Atomic overruns remain charged, including a prepared application that is closed before emission.

Only the existing private application method issues the exact-theory capability. The final verifier independently rechecks source, occurrence, substitution, target, requirements and evidence. Model and provider digests bind identities, not mathematical truth.

The historical eager provider, old entry points, theorem serialization and evidence schema remain unchanged. Full drain preserves its declared transformation/evidence order: preorder occurrences, shaped entries before wildcard entries, utility then reduction then ID. Mechanical work intentionally differs. Subset, match and candidate limits remain incomplete results, not evidence that no alternative exists.

## Audited use

Use `WorkReplacementLearning.prepareSchemas(...)` with the existing lifecycle journal to charge actual index preparation and its materialized receipt. Reuse the returned immutable plan in a loaded stream. After restoring in a fresh process, prepare again and pay again. Connect `prepared.provider()` using `TypedLearnedMoveInventory.newSchemaSearchSession(model, List.of(provider))` and `STAGED_INCREMENTAL`; the existing session dispatches primitive and schema verification.

Calling the low-level preparation API outside the audited adapter requires the caller to account for `compilationReceipt()` explicitly. Repeated preparation must not be disguised as a free cache hit.

## Regression evidence and limits

The executable eager checkpoint `eaa2cba057015ef8cc36f65d175ddef18ffe2536` compiled and ran under Java 25 in CI run 35632380158. Of 956 learning tests, exactly the two new intended regressions failed: an initial pull generated both available applications, and distinct configuration reused one registration identity. This is observed behavioral RED, not a compilation failure.

The subsequent implementation adds:

- A genuinely learned, serialized and restored two-occurrence schema; an adequate first result in the existing search kernel must leave the second application and evidence uncreated. The chosen proof is independently replayed.
- Complete-drain parity, nested substitutions, deep occurrences, near misses, unsupported domains, missing prerequisites, copied configuration, zero allowance, resumed small allowances, and closing a paid pending application.
- Loaded and fresh-process preparation accounting through the P01 adapters and existing subprocess protocol. Every cold process re-proves and recompiles. The primitive control is not charged for a nonexistent learned model.

`P03_MECHANISM` reports eager/lazy query work, final replay and explicit preparation cost separately. `P03_SETUP` retains the original diagnostic setup accounting; some streams differ in length, so these counters must not be converted into a speedup ratio.

### Matched logical-work diagnostic

`P03_MATCHED` adds the same two public development queries in the same order for B1 and L-oracle. Both arms use the same primitive inventory, objective, search limits, final verifier and total budget of 20,000,000 declared logical work units. In the warm configuration each arm reuses one fresh worker process; in the cold configuration each request gets a new worker. All actual restoration and compilation is paid in either case.

Before each request the allowance is `max(0, totalBudget - workAlreadyPaid) / requestsRemaining`. B1 therefore retains its unspent acquisition share instead of being restricted to the learned arm's query allocation. The oracle is charged the actual provisioned acquisition receipt, including the diagnostic hint, once per stream. Acquisition is not re-executed in every diagnostic arm, so this is a logical-work comparison, not a measured wall-clock training or full-lifecycle speedup.

The emitted records retain every returned score, quality miss, proof result, process identity, allocation, query receipt total and all eight lifecycle phase totals. Accounting or process failures fail the regression rather than becoming successful empty samples. No assertion requires the oracle to win. It remains a supplied-rule diagnostic, not L1 or a learned selection policy, and cannot establish a full B1/L1 economic advantage.

### Fresh-process accounting integrity

The subprocess protocol carries an explicit boolean `accountingComplete`. Known partial receipts remain in the parent journal, but a false, missing or non-boolean marker makes the parent account incomplete. This prevents the process boundary from erasing an incomplete provider receipt even when an evaluation separately claims a valid output.

The tests-only checkpoint `2d165776ec9ce7f5136ad160f2d22375cd1615bb` ran under Java 25 in CI run 35635934703. Of 966 learning tests, exactly the three new incomplete/missing/malformed marker cases failed at their parent-account assertion; the explicit-complete control did not fail. The fix follows this observed RED. Its provenance is retained in `evidence/work-replacement/p03-child-accounting-red.json`; this is not a claim that the corrected head has already passed CI.

Historical acquisition still uses the existing trace learner's fixed scoring contract; aligning and persisting that executable acquisition objective remains P06/P07 work. The public algebra examples are development/instance-transfer cases, not sealed family holdouts. P04–P12 are not implemented by this change. No factor-two or factor-ten result is claimed.

Run the affected regressions with Java 25 and the checkout's Gradle wrapper:

```sh
./gradlew --no-daemon :regelsuche-search:test :regelsuche-learning:test
```

The exact final PR head still requires all normal CI authorities and review before integration. No workflow, required check, quality threshold, frozen benchmark, or proof guarantee is relaxed.
