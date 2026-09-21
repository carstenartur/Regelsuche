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

`P03_MECHANISM` reports eager/lazy query work, final replay and explicit preparation cost separately. `P03_SETUP` reports diagnostic setup accounting, not a speedup ratio: its supplied-schema selection is deliberately diagnostic and some streams have different lengths. These tests do not establish a full B1/L1 economic advantage or a learned selection policy. No factor-two or factor-ten result is claimed.

Historical acquisition still uses the existing trace learner's fixed scoring contract; aligning and persisting that executable acquisition objective remains P06/P07 work. The public algebra examples are development/instance-transfer cases, not sealed family holdouts. P04–P12 are not implemented by this change.

Run the affected regressions with Java 25 and the checkout's Gradle wrapper:

```sh
./gradlew --no-daemon :regelsuche-search:test :regelsuche-learning:test
```

The exact final PR head still requires all normal CI authorities and review before integration. No workflow, required check, quality threshold, frozen benchmark, or proof guarantee is relaxed.
