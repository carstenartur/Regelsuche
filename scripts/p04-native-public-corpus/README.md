# Public native P03 differential driver

This test-only driver characterizes the native Expr path against the frozen public
P03 development corpus and the current legacy adapter. It calls the existing
`NativeMoveSearch` and `TypedMoveSearch` entry points. It introduces no production
search, matching, learning, or mathematical authorization mechanism.

The production tree must be integrated and committed before the full run. The
runner requires its exact commit, rejects dirty production sources and changed
public corpus bytes, compiles into a new local `/tmp` directory, then runs two fresh
JVMs. It never updates the historical files. No protected evaluation fixture is
used. The manifest binds all 15 public result files and `LegacyExprCorpus.java`.
The exact class-directory path is retained in the source binding; reports and
logs remain in the requested evidence directory. This avoids synchronized
temporary or stale bytecode entering the execution path.

## Run

Requirements: Python 3, JDK 25, the listed six Jackson/SnakeYAML JARs already in
the Gradle cache, and the original public corpus directory. No network download
is attempted. Builds and the two-process matrix must use the coordinator's
serialized build slot. A full run can take several minutes.

Set `--repo` and `--source-commit` to the final integrated checkout and its full
commit. Choose a new output directory on every attempt:

```sh
python scripts/p04-native-public-corpus/run.py \
  --repo /workspace/scratch/4e87c3dfd845/Regelsuche-p04-tree-position \
  --source-commit FINAL_INTEGRATED_COMMIT \
  --baseline /workspace/scratch/4e87c3dfd845/evidence-20261009/pr1065-recovered/pr1065-p04-structural-index/public-p03/baseline/results \
  --output /workspace/scratch/4e87c3dfd845/evidence-20261009/p04-native-public-corpus/final-matrix-1 \
  --diagnostic-work 1000000000 \
  --export-work 10000000
```

Adding `--compile-only` performs the pinned-source compile without executing
training or any search. This is separate from the one-file harness syntax check
against existing GREEN classes; that earlier check does not qualify the final
production tree.

The small report-boundary tests do not execute Java or the corpus:

```sh
python -m unittest discover -s scripts/p04-native-public-corpus -p test_protocol.py -v
```

## Coverage and interpretation

Every one of the 24 frozen search cases and both quality cases runs twice per
JVM: once with the exact historical numeric budget and once with the explicitly
declared diagnostic work ceiling. The latter changes only `totalWork` for both
current adapters. Other depth, theory, state, debt, policy, order, assumption and
continuation limits remain fixed. The ample run cannot rescue or overwrite a
historical-budget failure. Numeric work is not comparable between revisions.

The driver also executes all eight expression-identity pairs, three state
identity pairs, ten cursor cases, and both independent negative replay cases.
The cursor rows cover the historical allowances, stops between application
phases, missing assumptions, an unsupported domain, zero allowance, repeated
close, post-close pulls, exact ordered eager/pull transformations, and independent
replay of every emitted candidate. They retain all snapshots, observed costs and
failed attempts. The pull ceiling remains 10,000, as in the original driver.

Training uses the real `TraceRewriteStrategyLearner` public fixture, freezes its
checked schema model, restores it through `CheckedLearnedSchemaModel.load`, and
checks the restored artifact against the frozen model. Learned programs and
schemas use their existing native providers and registered native verifiers.
The test-only conditional primitive is an explicit retained wrapper around the
same pattern rule and assumption; it replaces the original generator's opaque
anonymous subclass without changing its mathematics. History filters use the
same public contract and leave authorization to the original registered verifier.

`comparison.json` separates three questions:

- Semantic projection parity: complete ordered witnesses, events, state sets,
  assessments and proof data, including assumptions, source bindings, hashes,
  verification receipts and continuation state. Only declared work fields and
  staged execution receipts are removed from this projection. The latter remain
  available unchanged in the raw reports and the dedicated cursor checks.
- Observed work: production search and final replay receipts, direct-call
  observations, preparation/restore declarations, and their explicit revisions.
  Overlapping declarations are never added into an unsupported lifecycle total.
- Output: a separate explicit production export budget and receipt, all exported
  proof bytes, and a check that export did not change the published search bill.

The native public outcome stays `INCONCLUSIVE`; a separately named diagnostic
comparison uses `observedOutcome`. It never changes the raw result or promotes
partial accounting. `accountingComplete`, `withinBudget` and economic claims
remain false. The full set of eight internal codec counters must be present and
zero. Import precedes this observer; explicit export has separate counters. A
missing counter, unavailable projection or caught failure cannot pass the check.

The direct cursor/replay/eager observation sink is a test instrument, not a new
production ledger. Its revision is `public-native-observer/v1`; provider receipts
remain separate. Harness import, construction, report serialization, filesystem
I/O and JVM/runtime work are not completely metered. The report therefore cannot
establish total CPU, memory, lifecycle economics or the completion of P04.

Rows with errors are preserved. `coverage.json` means that every row was attempted,
not that it passed. `searchSummary` lists every error, semantic difference,
missing/nonzero codec observation and changed or unavailable export by budget
mode. Fresh-JVM byte reproducibility is reported separately from semantic parity.
Inspect both the summary and all cursor/identity/negative-replay/quality relations
before drawing an acceptance conclusion. Reproducibility alone proves none of
those relations.

The evidence binds production source/resources, commit/tree, harness files,
public fixture bytes, JDK executable/modules/release, cached dependency bytes,
the complete commands and every output JSON. The runner rechecks input bindings
after compilation and each fresh process. Failed compilation, JVM failure or
timeout leaves the attempt and logs intact.
