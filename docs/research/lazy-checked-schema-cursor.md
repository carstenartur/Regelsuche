# P03 — demand-driven checked-schema applications

This opt-in implementation follows integrated P02 at `f751f90f58ee10aa122c42218b66a13760053aa0`. It uses the existing learner, theorem restoration, private checked-application capability, registered incremental provider and typed search frontier. It adds no search or learning algorithm.

## Execution and proof boundary

`CheckedSchemaMatcherPlan.prepare(model, maximum, utilities, included)` validates and copies configuration, prepares an immutable root-shape index and reusable bounded matcher wrappers, and returns an explicit compilation receipt. This preparation is not a theorem proof or a cache authorization. The model must have been learned or independently restored through `CheckedLearnedSchemaModel`.

Each provider session maintains a preorder occurrence stack, the current indexed schema range, and at most one completed match or application in progress. Applications retain their substitution-domain iterator, instantiated target and completed validation across pulls. Separate returning phases check each substitution's domain, instantiate/replace, check the target domain and construct evidence. A positive remaining allowance admits one bounded phase; its actual atomic overshoot is paid. The matcher and each individual domain traversal remain bounded atomic operations. Closing before evidence construction avoids that work. Closing after completion but before emission keeps the completed application's payment.

The eager provider, lazy cursor and independent verifier execute the same private application state machine. Only its completed evidence phase issues the existing exact-theory capability. The verifier independently rechecks source, occurrence, substitution, target, requirements and evidence. Neither intermediate state nor a prepaid receipt grants authority; model and provider digests bind identities, not mathematical truth.

The historical eager provider, old entry points, theorem serialization and evidence schema remain unchanged. Full drain preserves its declared transformation/evidence order: preorder occurrences, shaped entries before wildcard entries, utility then reduction then ID. Mechanical work intentionally differs. Subset, match and candidate limits remain incomplete results, not evidence that no alternative exists.

### Phase payment contract

The phase cursor opts into `regelsuche.incremental-provider/v3-prepaid`, `regelsuche.checked-schema-cursor-work/v2-prepaid-phases` and the staged execution receipt `regelsuche.staged-incremental-move-search-work/v3-prepaid`. A work receipt's optional `prepaidApplications` extension uses `regelsuche.prepaid-application-work/v1`. Native/v2 work omits that extension and retains its previous projection and serialized fields. `ExecutionWork` itself is unchanged.

A meter-owned, single-use ticket accumulates each actually executed phase's work immediately. Its receipt separates `phaseWork` from `phaseCalls`; each phase call adds one explicit mechanical event. Completion requires exactly one exact-theory step whose work equals that ticket's accumulated application work. It records the full completed mathematics but adds no second payment. Abandonment never refunds work, and foreign, repeated, mismatched or overflowing operations reject without partially updating the account.

Use `work.metrics().totalWorkUnitsV2()` for the paid total. Its mechanical channel contains wrapper/delegated operations plus cumulative prepaid application units and phase events. Its `candidateWork` contains only mathematics that was not prepaid. The separate `work.mathematics()` field contains all completed mathematics, including prepaid applications, for admission checks and diagnosis. Adding that full diagnostic field to the mechanical channel would double count prepayments and is not this version's accounting formula. All channels used by the search ledger remain monotone during settlement, early close and failure.

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

Historical measurements at `e77ee2e97f1c7ee24a6324da73af81de570a76be` and `db6d93b17b4050fc485f2ae5f59fb4a8208d7291` belong to `regelsuche.checked-schema-cursor-work/v1`: eager/lazy query work 197/154, final replay 51/51, preparation 450. They are not measurements of the subsequent prepaid phase revision. The original raw evidence is preserved; fresh correction runs report their own phase/event costs.

The September 27 correction run of the full core/search/learning suites uses `regelsuche.checked-schema-cursor-work/v2-prepaid-phases`: eager/lazy query work is 197/159, final replay remains 51/51, and preparation is 465. The additional phase events and changed configuration binding are paid. These small fixture results establish the lazy mechanism, not a full lifecycle economic advantage.

### Matched logical-work diagnostic

`P03_MATCHED` adds the same two public development queries in the same order for B1 and L-oracle. Both arms use the same primitive inventory, objective, search limits, final verifier and total budget of 20,000,000 declared logical work units. In the warm configuration each arm reuses one fresh worker process; in the cold configuration each request gets a new worker. All actual restoration and compilation is paid in either case.

The shared primitive inventory is compiled in both arms, but the active provider rosters differ: B1 exposes its primitive providers; the prescribed oracle selection exposes only the supplied schema provider and suppresses alternative primitive providers. This diagnostic therefore does not measure merely adding learned knowledge to an otherwise unchanged active roster. It isolates a supplied application, not learned selection or a B1/L1 advantage.

Before each request the allowance is `max(0, totalBudget - workAlreadyPaid) / requestsRemaining`. B1 therefore retains its unspent acquisition share instead of being restricted to the learned arm's query allocation. The oracle is charged the actual provisioned acquisition receipt, including the diagnostic hint, once per stream. Acquisition is not re-executed in every diagnostic arm, so this is a logical-work comparison, not a measured wall-clock training or full-lifecycle speedup.

The emitted records retain every returned score, quality miss, proof result, process identity, allocation, query receipt total and all eight lifecycle phase totals. Accounting or process failures fail the regression rather than becoming successful empty samples. No assertion requires the oracle to win. It remains a supplied-rule diagnostic, not L1 or a learned selection policy, and cannot establish a full B1/L1 economic advantage.

Fresh correction measurements retain the outer diagnostic record format `p03-matched-logical-work/v1` while the embedded schema execution receipts use the prepaid revisions above. The same two-query stream produced:

| Process mode / arm | Training search | Formation/proof | Selection | Restore | Compile | Query | Final check | Output | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Reused / B1 | 0 | 0 | 0 | 0 | 8 | 336 | 18 | 125791 | 126153 |
| Reused / L-oracle | 212 | 3224 | 68 | 582 | 483 | 156 | 50 | 87763 | 92538 |
| Fresh per query / B1 | 0 | 0 | 0 | 0 | 16 | 336 | 18 | 129576 | 129946 |
| Fresh per query / L-oracle | 212 | 3224 | 68 | 1164 | 956 | 156 | 50 | 91795 | 97625 |

Excluding the materialized output channel, the oracle costs 4775 versus B1's 362 in the reused process and 5830 versus 370 with fresh processes. Thus the apparent total-work advantage is driven by output size; the non-output comparison is negative. Acquisition is the paid historical scorer-based pipeline: the manifest objective applies to selection and queries, not acquisition. Executable acquisition-objective binding remains P06/P07 work.

### Fresh-process accounting integrity

The subprocess protocol carries an explicit boolean `accountingComplete`. Known partial receipts remain in the parent journal, but a false, missing or non-boolean marker makes the parent account incomplete. This prevents the process boundary from erasing an incomplete provider receipt even when an evaluation separately claims a valid output.

The tests-only checkpoint `2d165776ec9ce7f5136ad160f2d22375cd1615bb` ran under Java 25 in CI run 35635934703. Of 966 learning tests, exactly the three new incomplete/missing/malformed marker cases failed at their parent-account assertion; the explicit-complete control did not fail. The fix follows this observed RED. Its provenance is retained in `evidence/work-replacement/p03-child-accounting-red.json`; this is not a claim that the corrected head has already passed CI.

Historical acquisition still uses the existing trace learner's fixed scoring contract; aligning and persisting that executable acquisition objective remains P06/P07 work. The public algebra examples are development/instance-transfer cases, not sealed family holdouts. P04–P12 are not implemented by this change. No factor-two or factor-ten result is claimed.

Run the affected regressions with Java 25 and the checkout's Gradle wrapper:

```sh
./gradlew --no-daemon :regelsuche-search:test :regelsuche-learning:test
```

The exact final PR head still requires all normal CI authorities and review before integration. No workflow, required check, quality threshold, frozen benchmark, or proof guarantee is relaxed.
