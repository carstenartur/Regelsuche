# Capability coverage and context estimates

Issue #984 repairs the `rewrite-search` context, not the production architecture.
The seed explicitly selects `de.regelsuche.search.reachability`, preparation
and replay tests, and the coordinator/work-accounting documentation. No classes
were merged or renamed to improve a score.

## Executable coverage contract

`config/ai-knowledge-capability-coverage.json` binds these production boundaries:

- `SharedUnifiedRulePreparationCoordinator` — retained shared/V2 authority;
- `OccurrenceAwareSharedRulePreparationCoordinator` — occurrence-local V3 authority;
- `OccurrencePreparationReplay` — independent concrete downstream replay.

All are in `de.regelsuche.search.reachability`. Their named regression tests
must also occur in the extracted test inventory, capability and actual context
pack. The verifier distinguishes `BOUNDARY_NOT_EXTRACTED`,
`CAPABILITY_MEMBERSHIP_MISSING` and `CONTEXT_MEMBERSHIP_MISSING` from unresolved
selector references. Missing or mismatched context identities and duplicate
capability/index entries fail closed. The contract is deliberately narrow;
passing it does not prove complete coverage of every capability.

The eight checkout-owned Python controls include otherwise valid, nonempty
artifacts with a required coordinator or replay boundary omitted. They run in
the normal `aiKnowledgeCheck` chain before repository artifact verification.
Independent review also removed each of the three required types and three
tests separately from each of inventory, capability and context: all 18
omissions were rejected. Three additional controls rejected a foreign context
path, duplicate context index entry and absent required capability. The genuine
generated candidate artifact passed with no errors or warnings.

## Controlled measurements

The retained [machine-readable comparison](generated/ai-context-coverage-984.json)
contains source commits/trees, all seed/model-input hashes, the extractor JAR
hash, generated JSON hashes, inventory counts and all 17 capability working
sets. These are local static measurements, not a replacement for CI evidence.

Both architecture snapshots use exactly the corrected seed with SHA-256
`16d64630109485ca14a5c1566d64084f3b932d7edf52bf70facd4274de0b8d0b`:

- Base: `8afeda1a538c6726b0f6b8a6c8014dfcced0fbe2`, before #974.
- Candidate: `67ea97116db21ff4050b0ed33a34f5456b2581ea`, reviewed #974 code.

The model is the unmodified extractor release `0.1.10`, source commit
`b409bed957c31d63ce7b6ef37205890f0f0ebd9a`, `basic` Java extraction, `ast` JDT
mode and `context-footprint-v3`. Gradle 9.7.1 ran on Temurin 25.0.4.1+1; the
extractor was compiled with its declared Java 17 toolchain (Temurin 17.0.20.1+1).
Production and documentation come from the recorded source commits, before
this report was added. Later documentation adds its own measured overhead.
The base predates V3, so its corrected selectors cover the existing boundaries;
it is not claimed to pass the new V3-required coverage contract.

| Estimate | Base, corrected coverage | Candidate, corrected coverage | Change |
|---|---:|---:|---:|
| Legacy fixed-cost `estimatedContextTokens` | 558,010 | 559,950 | +1,940 |
| Line-weighted repository context tokens | 3,623,380 | 3,641,912 | +18,532 |
| Production context tokens | 2,438,520 | 2,447,120 | +8,600 |
| Test evidence tokens | 1,130,680 | 1,140,432 | +9,752 |
| Documentation tokens | 54,180 | 54,360 | +180 |
| `rewrite-search` production working set | 551,456 | 560,056 | +8,600 |
| Median capability working set | 99,240 | 99,240 | 0 |
| P90 capability working set | 619,528 | 619,528 | 0 |
| Normalized context debt | 17.10 | 17.01 | -0.09 points |
| Serialized `rewrite-search` context-pack estimate | 13,969 | 14,260 | +291 |

The candidate adds functionality and tests. Its absolute load and local search
working set increase. P90 remains constant while the repository denominator
increases, so the normalized value decreases. That is a property of this
normalization, not evidence that the architecture became easier to understand.
No baseline, threshold or blanket non-increase requirement was substituted for
this comparison.

## Coverage-only control on identical candidate code

A third extraction uses exactly the same candidate source with its original
seed (`831e6d021d1a2f92ed764c89a1520c4ee609f176d01b0d7d170b86c3e16bd6fb`).

| Candidate measurement | Original selectors | Corrected selectors |
|---|---:|---:|
| `rewrite-search` context types | 324 | 336 |
| `rewrite-search` context tests | 94 | 125 |
| Serialized context-pack token estimate | 13,134 | 14,260 |
| Production working set tokens | 560,056 | 560,056 |
| Normalized context debt | 17.01 | 17.01 |

The unchanged numeric values have a concrete cause in extractor 0.1.10:
`CapabilityLinker` matches package selectors exactly when populating context
membership, while `ContextFootprintMetrics` also includes descendant packages.
The existing `de.regelsuche.search` selector therefore already counted
reachability production code numerically, although the context omitted the
coordinators. The corrected package fixes that membership mismatch. It does
not itself reduce or increase the production working-set estimate.

## Reproduction

Use clean worktrees at the two recorded source commits and a clean extractor
checkout at the recorded release commit. Copy the corrected
`ai-knowledge/capabilities.seed.yaml` into **both** source worktrees and verify
the hash above. Keep their other seed/model inputs unchanged; hashes are in
the retained JSON. For the third control, use candidate code with its original
seed. In each worktree, run the same extraction:

```bash
./gradlew --no-daemon --no-configuration-cache --max-workers=2 \
  -PenableAiKnowledgeExtractor=true \
  -PuseLocalAiKnowledgeExtractor=true \
  -PaiKnowledgeExtractorCheckout=../ai-knowledge-extractor \
  analyzeAiComplexity
```

Install both Java 25 and the extractor's Java 17 compiler, or declare their
locations with `-Porg.gradle.java.installations.paths=...`. Then use the reporting
script from the coverage-fix checkout (it is not present in the historical
source commits):

```bash
python3 /path/to/coverage-fix/scripts/report-ai-context-working-sets.py \
  --root build/ai-knowledge \
  --extractor-core-jar ../ai-knowledge-extractor/core/build/libs/core-0.1.10.jar \
  --output build/reports/ai-context-working-sets.json
```

The script invokes the actual supplied extractor model once for the whole
snapshot and once per capability. It rejects an aggregate that differs from
the retained `complexity.json`; it does not reimplement or fit the formula.
The regular `aiKnowledgeCheck` remains the quality gate. This report does not
refresh its historical baseline or certify a different checkout's CI result.

## Interpretation limits

These are deterministic proxies, not observed LLM tokenizer usage, human
comprehension times, runtime memory or latency. The model weights observed
production/test lines by eight and documentation lines by six, with fixed
fallbacks when line counts are unavailable. Capability working sets count
selected production facts; repository totals also include tests and documents.
The serialized pack estimate measures a compact index of paths and names,
not the full source needed to work on that capability. The legacy fixed-cost
estimate is another quantity and must not be mixed with the line-weighted one.

The normalized score uses P90 production working-set size divided by repository
context size, plus an evidence-coverage penalty. The penalty is zero for these
snapshots. Additional functionality can reasonably increase absolute context;
more tests can lower a normalized percentage without reducing task-local work.
A lower score cannot establish fewer tokens for an unknown future task.
