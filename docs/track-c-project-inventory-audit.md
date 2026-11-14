# Track C candidate matches against historical project inventories

The recurrence comparison emitted three different equations. One was already in the historical recurrence adapter’s output. The other two fit the same geometric TRAIN prefix and had no exact coefficient-vector match in that catalogue. The finite-difference comparison recovered a previously recorded square-sequence fit. The rational arms either selected the full historical guarded operator or one operation it already contained. A macro marked `NEW` by the seven-rule project checker had also appeared in an earlier project macro run, with a different operation sequence.

The retrospective comparison uses project revision `34eb0900`, which predates all four comparative runs. The audit protocol fixes the inventories and matching semantics. All 48 frozen campaign-arm slots enter the report, including seven empty ones. Each occurrence below counts one selected candidate in one campaign-arm slot. The four campaigns share the same public cases. [Audit protocol](https://github.com/carstenartur/Regelsuche/blob/2c0f29e939d83fc1b44143c974ad0f3aec671027/research/benchmarks/candidate-independent/track-c-project-inventory/audit-plan.json) · [Earlier project revision](https://github.com/carstenartur/Regelsuche/commit/34eb0900f26f9791545e0201d2f5db2d4a2ee96f) · [All rows and match receipts](../research/benchmarks/candidate-independent/track-c-project-inventory/audit-result.json.gz)

| Frozen comparison | Empty slots / 12 | Selected occurrences | Distinct candidates | Historical comparison |
|---|---:|---:|---:|---|
| Linear recurrences | 1 | 13 | 3 | 9 occurrences matched the coefficient vector `[2]`. Four occurrences of `[1,2]` or `[3,-2]` lacked an exact match. |
| Newton finite differences | 6 | 6 | 1 | All six matched `[1,3,2]`, including the first observed value. |
| Guarded rational programs | 0 | 12 | 2 | Eight selected both historical branches. Four selected its cancellation branch alone. |
| Native two-step macros | 0 | 23 | 2 | Eleven matched a directed historical pattern with unchanged assumptions and different operations. Twelve lacked a directed pattern match. Neither program exactly matched a historical macro. |

The sequence catalogue includes every available formation and evaluation row from the four historical campaigns. A matched row keeps its original outcome, including a refutation if present. For finite differences, the historical adapter records its confirmed result and prefix. The audit reconstructs the initial differences through the first constant row, following the domain’s extraction rule. For recurrences it compares the exact rational coefficients in order. Equivalent predictions on a finite prefix do not collapse distinct coefficient vectors.

The macro checker compares canonical patterns in both directions against seven active known rules. Both selected patterns received its native `NEW` status. The historical macro comparison separately checks the native directed pattern key, ordered abstract operations and assumptions. The pattern `p1*1+0→p1` appeared previously with identity multiplication followed by addition of zero. The new candidate used addition of zero followed by collection. Its project history is therefore more specific than its seven-rule checker status. The rational inventory comes from the native historical class and candidate manifest. Its two operation branches are common-factor cancellation and equal-denominator addition/subtraction.

| Form | Observed support | Falsification and strength | Held-out evidence |
|---|---|---|---|
| Recurrence | Every emitted model had support from the single geometric TRAIN case. | Exact rational prefix comparisons and withheld finite continuations. The raw rows retain failed equations, duplicate draws and a zero-output campaign. | Zero confirmed VALIDATION or TEST case slots in every arm. |
| Finite differences | Every emitted polynomial had support from the square-sequence TRAIN case. | Exact integer difference tables and finite predictions. The ordered grammar exhausted its allowance without an admitted model. | Zero confirmed VALIDATION or TEST case slots in every arm. |
| Guarded rational | Each selected program had four witnesses across two TRAIN structural clusters. | Cross-multiplied polynomial normal forms agree under the supplied nonzero premises. Unreached targets retain the exhausted search frontier. | Each arm reached 0/16 VALIDATION and 8/16 TEST task slots. This measures guarded reachability. |
| Macros | Each pattern drew support from one TRAIN structural cluster. Both clusters are represented across the two patterns. | Native symbolic polynomial-normal-form validation and atomic rewrite witnesses. The seeded arm retains one rejected replay group. | Paired primitive-only and macro-enabled searches found no VALIDATION or TEST gain or correctness regression. |

The raw audit retains each candidate’s support cases and structural clusters, native evidence strength, scope of utility, per-split outcomes and available rejection or witness records by campaign and arm. The link to the frozen input identifies the full source paths and attempted proposals. A missing catalogue match concerns only the stated historical project inventory. No external novelty or mathematical uniqueness assessment was performed. The four measurements have different outcome and cost units.

## Reproduce the audit

After the four comparative artifacts are present in a checkout, run the verifier. It checks the original input bytes, rebuilds every slot, and compares the complete audit with the retained bytes.

```sh
python3 scripts/audit-track-c-project-inventory.py
```

A checkout with the four PR commits can instead read the exact Git blobs, without merging the branches. This also works before the remaining comparative PRs have landed.

```sh
git fetch origin refs/pull/1239/head refs/pull/1241/head refs/pull/1243/head
python3 scripts/audit-track-c-project-inventory.py --git-repository .
```

To reproduce the historical catalogue independently, build the two distributions at the pinned revision in a separate checkout. The historical replay runs the four original adapters and checks their complete outputs and the active rule catalogue. A compiled Maven runtime classpath can also be supplied through `--classpath`.

```sh
git worktree add --detach /tmp/regelsuche-prior-track-c 34eb0900f26f9791545e0201d2f5db2d4a2ee96f
(cd /tmp/regelsuche-prior-track-c && ./gradlew :app:installDist :regelsuche-release:installDist)
python3 scripts/replay-track-c-historical-inventory.py /tmp/regelsuche-prior-track-c
```

Two historical executions reproduced the 244,634-byte normalized adapter bundle with SHA-256 `a5436121ea5cabba21a685f95e02f82d17a7f820acfae0c37f44a259908da687`. Filesystem and immutable-Git-blob replay reproduced the 157,528-byte audit with SHA-256 `6fd69dc66f4ce982dc3314c3082104cea2543b5cfc30ba68831fe4836a503af7`.
