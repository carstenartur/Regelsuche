# AI knowledge index

Regelsuche consumes the released `org.aiknowledge.extractor` Gradle plugin from
the `carstenartur/ai-knowledge-extractor` GitHub Packages repository. The
consumer version is pinned once in `gradle.properties`:

```properties
aiKnowledgeExtractorVersion=0.1.10
```

Version 0.1.10 retains the generated-`build/` and `target/` inventory pruning
introduced in 0.1.9 and fixes the context-debt trend contract. The absolute
quality gate always checks the current measured, normalized `aiContextDebt`
against `maxCognitiveDebt`. Separately, the debt trend gate compares normalized
`aiContextDebt` only when the baseline and current snapshot declare the same
context-debt model. The former `aiCognitiveDebt` value remains available as a
legacy diagnostic; it is no longer allowed to fail a normalized context-debt
trend merely because the two metrics move differently.

Regelsuche never consumes a snapshot implicitly. Updating the released
dependency requires one explicit version change followed by the normal
AI-Knowledge and repository verification lifecycles.

Semantic capability coverage is checked separately from numeric selector
resolution. The repository-owned
`config/ai-knowledge-capability-coverage.json` requires both preparation
coordinators, the current concrete replay boundary and their regression tests
to exist in the extracted inventory, the `rewrite-search` capability and its
actual context pack. Nonempty output and zero unresolved references do not
satisfy this contract. See [coverage and controlled comparison](ai-context-coverage.md)
for the negative controls, measurement identities and interpretation limits.

## Released package mode

AI Knowledge is optional so an ordinary Regelsuche checkout does not need
GitHub Packages credentials. Enabling the lifecycle adds the dedicated
`ai-knowledge-verification` consumer project. That project applies the released
plugin through the canonical Gradle plugin DSL:

```groovy
plugins {
    id 'org.aiknowledge.extractor'
}
```

`settings.gradle` supplies the version and the GitHub Packages plugin repository.
Provide a GitHub identity and token with package-read permission:

```bash
GITHUB_ACTOR=<github-user> \
GITHUB_TOKEN=<package-read-token> \
AI_KNOWLEDGE_EXTRACTOR_ENABLED=true \
  ./gradlew --no-configuration-cache aiKnowledgeCheck
```

GitHub Packages is an authenticated Maven repository even for this public source
repository. The credential requirement is a package-registry boundary, not a
reason to copy plugin verification semantics into GitHub Actions.

## Explicit local plugin development

A sibling checkout does not override the released plugin merely because it
exists. To test changes to the extractor before releasing them, opt in
explicitly:

```bash
AI_KNOWLEDGE_EXTRACTOR_ENABLED=true \
  ./gradlew --no-configuration-cache \
  -PuseLocalAiKnowledgeExtractor=true \
  -PaiKnowledgeExtractorCheckout=../ai-knowledge-extractor \
  aiKnowledgeCheck
```

In this mode `pluginManagement.includeBuild(...)` supplies the same plugin id via
a Gradle composite build. No GitHub Packages credentials are required. The local
checkout path defaults to `../ai-knowledge-extractor`, but the override remains
explicit and visible in the command line.

## Complete verification lifecycle

The root `aiKnowledgeCheck` task composes, without duplicating task names:

1. the plugin-native `:ai-knowledge-verification:aiKnowledgeCheck`, which creates
   one complete repository snapshot and emits the index, complexity,
   optimization, benchmark and quality-gate artifacts;
2. the plugin-native, read-only `verifyAiKnowledgeArtifacts`, which validates
   the complete structural artifact set;
3. `verifyRegelsucheAiKnowledgeArtifacts`, which checks evidence and the
   measured schema-v3 context-footprint contract;
4. `verifyComplexityHotspots`, which rejects new or materially worsened method
   hotspots unless a finite policy exception exists.

The two post-verifiers only read the snapshot after the plugin-native lifecycle
has completed. They therefore neither rescan generated build trees nor compete
with artifact writers. The extractor currently requires configuration-cache
opt-out. All pass/fail semantics nevertheless live in the checkout rather than
in GitHub Actions.

`scripts/verify-ai-knowledge-artifacts.py` rejects missing or empty files,
malformed or duplicate-field JSON, empty evidence, missing context packs,
unresolved capability references and drift from the measured context-footprint
contract. A missing `methodFacts` section remains an explicit warning rather
than a build failure.

The generated files are written to `build/ai-knowledge/`. Important artifacts
include `index.json`, `modules.json`, `classes.json`, `tests.json`, `docs.json`,
`capabilities.json`, `dependencies.json`, `claims.json`, `evidence.json`,
`complexity.json`, `review-context.md` and `context-packs/index.json`.

## Individual extractor tasks

Root-project aliases preserve the plugin commands while delegating to the
optional versioned consumer project:

```bash
./gradlew generateAiKnowledgeIndex
./gradlew analyzeAiComplexity
./gradlew optimizeAiKnowledge
./gradlew benchmarkAiKnowledge
./gradlew checkAiKnowledgeIndex
./gradlew verifyAiKnowledgeArtifacts
./gradlew publishAiKnowledgeIndex
```

`verifyAiKnowledgeArtifacts` is deliberately a read-only verifier for an
existing complete artifact set. Use `aiKnowledgeCheck` for the full generated
and Regelsuche-qualified lifecycle. The repository-specific stages can also be
addressed directly when diagnosing a failure:

```bash
./gradlew :ai-knowledge-verification:verifyRegelsucheAiKnowledgeArtifacts
./gradlew :ai-knowledge-verification:verifyComplexityHotspots
```

The extractor produces the two method rankings in `complexity.json`. Maven can
apply the same Java policy decision to a retained extraction using the following
command from the repository root.

```bash
mvn -pl regelsuche-quality -am test -Dtest=ComplexityHotspotsTest \
  -Dregelsuche.complexityResult=/absolute/path/to/complexity.json
```

The JSON decision and Markdown table are written to
`regelsuche-quality/target/reports/quality/complexity`. A policy violation
writes both diagnostics and fails the build. The Gradle lifecycle invokes the
same verifier immediately after extracting the current checkout.


These commands require the same enablement flag as `aiKnowledgeCheck`.
`checkAiKnowledgeIndex` evaluates rule-bearing claim seeds from
`ai-knowledge/claims.seed.yaml`. Claims marked with `severity: error` fail the
build; `warning` claims remain advisory.

`publishAiKnowledgeIndex` copies the generated snapshot to
`docs/ai-knowledge/` when a committed documentation snapshot is desired. The
extractor is deterministic and does not require external LLM or SaaS calls.

## CI boundary

AI Knowledge no longer owns a separate workflow. The single verification
workflow invokes the checkout entrypoint `ciCheck` with
`AI_KNOWLEDGE_EXTRACTOR_ENABLED=true`. The Gradle task then includes the root
`aiKnowledgeCheck` aggregate in the same graph as the rest of repository
verification. Generated files are retained by the generic
repository-verification artifact.

The exact GitHub execution can be reproduced locally with the released package
command above, or without package credentials by selecting the explicit local
composite build. Authentication changes how Gradle resolves the plugin; it does
not change which tasks, assertions or artifact contracts are executed.

## Context-debt trend baseline

### Accepted main after scoped-proof integration (2026-11-24)

The current reference is accepted main `f183aedd1ff2ab061ed903559b96a11d28974e7f`,
tree `c5764e9bdb462df4ac4d92a06532c169d96f68cb`.
[Original main CI 45290302102](https://github.com/carstenartur/Regelsuche/actions/runs/45290302102)
passed all thirteen mandatory source authorities, the aggregate `ciCheck` and
report publication. The four optional manual JMH study jobs were skipped. The
downloaded quality artifact `14878535049` matches its API-reported ZIP digest;
all 293 indexed file lengths and SHA-256 hashes were verified, and its 64 tracked
source files match the exact accepted commit. Its index binds a clean checkout.

The original native check passed at **876,100** estimated context tokens and
concept radius **121**: **+14,915** tokens and **+2** radius units against the
previous 861,185-token reference, within the unchanged 15,000-token and
three-radius allowances. The [active snapshot](../ai-knowledge/baseline-history/2026-11-24-main-f183aedd/metrics-snapshot.json)
is a complete projection of that accepted complexity report through the original
extractor 0.1.10 `TrendAnalyzer.snapshot` and `StableIo.writeJson`. The quality
artifact did not retain its native snapshot. The original classes are bound to
their released commit and Git blob hashes; no baseline fields were selected by
hand. [Provenance and reproduction](../ai-knowledge/baseline-history/2026-11-24-main-f183aedd/README.md)
retain the original reports, source authorities, policy hashes and projection.

The separate SDK #1376 Gradle measurement recorded **876,785** tokens and failed
the former comparison at **+15,600**. Its original check, snapshot and trend are
retained. The unchanged native trend implementation reproduces the original
snapshot and trend bytes, then compares that same measurement with accepted main
at **+685** tokens and **+0** radius units. This is a retained-measurement replay,
not a new extraction or complete acceptance of #1376. Other source jobs in its
original run were still running when inspected. The reference contains no
feature-branch measurement, and updated candidates still require fresh extraction
and their complete integration CI. Thresholds, selectors and hotspot exceptions
remain unchanged.

### Integrated main after #1361 (2026-11-22)

The preceding reference comes from main `9d1d4ac0ece4fd056cf9f952795772feba6baa82`,
tree `eb8e739a8084b063c5f480837fc1da2f78cbcd48`. The fully successful
[#1361 CI](https://github.com/carstenartur/Regelsuche/actions/runs/45030696246)
tested GitHub merge commit `28c7446500ab435f4fca30f093b5a2509f3387be` with that
identical tree. All eleven source jobs, the aggregate gate and CodeQL passed.
The separate push run for `9d1d4ac` was cancelled. The retained CI artifact binds
the clean tested checkout and all 293 indexed files by length and SHA-256.

The [retained snapshot](../ai-knowledge/baseline-history/2026-11-22-main-9d1d4ac/metrics-snapshot.json)
projects the original passing extractor 0.1.10 measurement of **861,185** context
tokens and concept radius **119**. The later main `d599e2d` measurement of 873,185
tokens had exceeded the prior reference by 17,980. The same measurement replays
at **+12,000** tokens and **+2** radius units against #1361 under the existing
15,000-token and three-radius limits. The [CI evidence and original failure](../ai-knowledge/baseline-history/2026-11-22-main-9d1d4ac/README.md)
bind both source trees and report identities. The updated checkout still receives
fresh extraction and complete integration CI.

### Accepted main after Maven and proof-authority integration (2026-11-22)

The preceding reference is the original extractor 0.1.10 projection of accepted main
`98a60e30629f65bc12bb573e9beb46b04053e66c`, tree
`e62f351b3aa4c9a7901d0737b85db8443c50505b`.
[Main CI 45008332052](https://github.com/carstenartur/Regelsuche/actions/runs/45008332052)
passed eleven mandatory source authorities, the aggregate gate and report
publication. All 293 indexed files in its original quality artifact matched their
reported lengths and SHA-256 checksums. The native check passed against the
previous 847,010-unit reference: **855,205 context units**, an increase of **8,195**
within the unchanged **15,000** allowance. Concept radius increased from 117 to 118.

The [accepted-main provenance](../ai-knowledge/baseline-history/2026-11-22-main-98a60e30/provenance.json)
binds the source, original CI, artifact digest, unchanged policies and retained
native reports. The [projected snapshot](../ai-knowledge/baseline-history/2026-11-22-main-98a60e30/metrics-snapshot.json)
remains in the baseline history. Pending feature branches supply no baseline values and
still require their own complete acceptance. Thresholds, selectors and exceptions
retain their existing definitions.


### Accepted main after witness/induction integration (2026-11-21)

[Main CI 44852121730](https://github.com/carstenartur/Regelsuche/actions/runs/44852121730)
completed successfully on immutable `main` revision `a5e2efc099162723ca2f3f8ca7064a98fddb52cb` (tree `38e161b03b68bbd36261d5908b4afa14bd6a5f88`):
all seven mandatory verification authorities, the aggregate `ciCheck`, and report publication
passed. The original retained [quality evidence](https://github.com/carstenartur/Regelsuche/actions/runs/44852121730)
(artifact 14691325547; ZIP SHA-256 `6321089fa1b2d4a1c1af2e7dc5c430adb1aa488cf4915e424931a2a0c77e3a01`)
has 290 unique indexed files; all reported byte lengths and SHA-256 checksums were independently
matched. The original `check.json` passed and the native `complexity.json` measures
832860 context units, up 3290 from the previously accepted 829570, within the **unchanged**
15000 limit. The verified source and CI linkage is retained in
[provenance](../ai-knowledge/baseline-history/2026-11-21-main-a5e2efc0/provenance.json)
and [metrics snapshot](../ai-knowledge/baseline-history/2026-11-21-main-a5e2efc0/metrics-snapshot.json).

This accepts **only this green predecessor main**, not any current feature branch.
No threshold, exception, policy, code selector, unpublished material, or protected test input is
changed. Feature PRs still require fresh complete exact-head CI and their own functional acceptance.



### Accepted main after scoped proof and continuation integrations (2026-11-20)

The clean accepted reference is `c268eedc94032b65293d09b4e3d9d6af256b1074`,
tree `8dc43f34aa9af5b06299468f9424933184de0086`.
[Main CI 44824761437](https://github.com/carstenartur/Regelsuche/actions/runs/44824761437)
passed all seven verification authorities, aggregate `ciCheck` and report
publication. Its original artifact binds 290 indexed evidence files.

A fresh detached checkout passed all 14 root `aiKnowledgeCheck` tasks in 43s,
including eight coverage controls and 17 hotspot checks. The original 825210-unit
baseline measured 829570 units and a concept radius of 115, with no violations
under the unchanged 15000-unit and 3-radius limits. The proposed reference copies
the complete generated [snapshot](../ai-knowledge/baseline-history/2026-11-20-main-c268eedc/metrics-snapshot.json).
[Provenance](../ai-knowledge/baseline-history/2026-11-20-main-c268eedc/provenance.json)
retains all 38 raw reports, the actual execution log and the original CI index.
The native check/complexity files are byte-identical to the accepted CI files.
The [independent review](../ai-knowledge/baseline-history/2026-11-20-main-c268eedc/independent-review.json)
rechecked the original ZIP, every indexed file, source/tool Git objects and the
unchanged controls. Pending feature measurements supply no baseline values.

### Accepted main with the mathematical certificates and domain workspaces (2026-11-20)

The reference is clean main `e115dca89bba32bad516d66f408e26f99fbb6149`, tree
`3fd5ec1d1e4248692aa2d862113b1bd00b1b56ce`. Its
[main CI](https://github.com/carstenartur/Regelsuche/actions/runs/44804422507)
passed all seven verification authorities, the aggregate `Checkout-local ciCheck`
and report publication. The original accepted artifact binds all 290 indexed
files to their exact lengths and hashes.

A fresh clean checkout passed the complete root `aiKnowledgeCheck` against the
unchanged 814350-unit predecessor baseline: 825210 context units, an increase of
10860 within the existing 15000 allowance. All 14 tasks, eight coverage controls
and 17 hotspot checks passed. Native `check.json` and `complexity.json` match the
accepted CI bytes. The new baseline copies the complete generated
[metrics snapshot](../ai-knowledge/baseline-history/2026-11-20-main-e115dca8/metrics-snapshot.json).
[Provenance](../ai-knowledge/baseline-history/2026-11-20-main-e115dca8/provenance.json)
retains all 38 raw reports, the original CI index, the actual execution log,
source/tool identities and the previous baseline. The
[independent review](../ai-knowledge/baseline-history/2026-11-20-main-e115dca8/independent-review.json)
checked the downloaded archive against GitHub's live digest, every retained
file, the unchanged policy bindings and the successful predecessor gates.

Only this already accepted main supplies reference values. Pending features
still require their own complete verification. Thresholds, selectors, claims,
exceptions and earlier rejected measurements remain unchanged.

### Accepted main before the November 20 mathematical integrations (2026-11-20)

The reference is clean main `f10e4c0875e1e76288b2332191bba5b78b2f53dc`, tree
`e199300eb814dbe2e829ae0128f6746900d05f87`. Its
[main CI](https://github.com/carstenartur/Regelsuche/actions/runs/44712656489)
passed all seven verification authorities, `Checkout-local ciCheck` and report
publication. The original quality-acceptance archive binds all 290 indexed
files to their lengths and SHA-256 hashes.

A fresh clean checkout passed the complete root `aiKnowledgeCheck` with the
unchanged pinned v0.1.10 extractor and original 802120-unit baseline: 814350
context units, an increase of 12230 within the existing 15000 allowance.
All eight coverage controls and 17 hotspot checks passed. The generated
`check.json` and `complexity.json` are byte-identical to the accepted CI reports.
The baseline is a byte-for-byte copy of the complete generated
[metrics snapshot](../ai-knowledge/baseline-history/2026-11-20-main-f10e4c08/metrics-snapshot.json).
[Provenance](../ai-knowledge/baseline-history/2026-11-20-main-f10e4c08/provenance.json)
retains source and extractor identities, the execution receipt, policy hashes,
the previous baseline and all 39 raw measurement reports. The
[independent review](../ai-knowledge/baseline-history/2026-11-20-main-f10e4c08/independent-review.md)
checks this predecessor promotion separately from later feature verification.

The later main `c621a1949d29c391ceb6f1bdb5e79bf6e263ccf1`
[failed its context trend](https://github.com/carstenartur/Regelsuche/actions/runs/44746311416)
at 818250 units: an increase of 16130 against the previous reference and 3900
against the accepted predecessor. Its original
[rejected trend](../ai-knowledge/baseline-history/2026-11-20-main-f10e4c08/rejected-trend.json)
is retained and supplies no baseline values. Thresholds, selectors, claims and
hotspot exceptions are unchanged. Each later checkout still requires its own
complete quality gates and CI.

### Accepted main after verified affine observation evaluation (2026-11-16)

The previous reference is clean main `e1a36d34f6d3652df05b95fc5fd0e8d416b5e301`.
All seven verification authorities, the aggregate `Checkout-local ciCheck`
and report publication passed. The pinned extractor measured 802120 context
units, up 14940 from 787180 within the unchanged allowance of 15000. The
[acceptance record](../ai-knowledge/baseline-history/2026-11-16-main-e1a36d3/provenance.json)
binds the clean checkout and all 287 indexed file lengths and SHA-256 hashes.
[Main CI](https://github.com/carstenartur/Regelsuche/actions/runs/43834020999)

The earlier [PR #1274 run](https://github.com/carstenartur/Regelsuche/actions/runs/43848888062)
measured 802960 context units. Its [rejected trend](../ai-knowledge/baseline-history/2026-11-16-main-e1a36d3/rejected-trend.json)
records an increase of 15780 against the previous reference. Replaying the
pinned trend check on that measurement against the accepted main snapshot
passes at an increase of 840. Only the accepted main measurement supplies
baseline values.

### Accepted main after the Track F integration (2026-11-15)

The reference is clean main `f7f244c4488ea09413861028e956c97c18a6fed9`.
All seven verification authorities and the aggregate `Checkout-local ciCheck`
passed and produced the quality acceptance artifact. The downstream report
publication was cancelled after verification. The pinned extractor measured
787180 context units, an increase of 12860 within the existing allowance of
15000. The acceptance record binds the clean tree and verifies the size and
SHA-256 of all 287 indexed files. [CI run](https://github.com/carstenartur/Regelsuche/actions/runs/43684112280) · [Acceptance record](../ai-knowledge/baseline-history/2026-11-15-main-f7f244c/provenance.json)

The earlier #1248 head `9acff854` measured 789720 units, an increase of 15400
under the previous baseline. Reapplying the unchanged trend check to that
recorded measurement with the accepted main baseline gives an increase of
2540. The rejected measurement contributes no baseline values. [Rejected CI run](https://github.com/carstenartur/Regelsuche/actions/runs/43687276553) · [Original trend report](../ai-knowledge/baseline-history/2026-11-15-main-f7f244c/rejected-trend.json)

### Accepted main before merging the public discovery and workbench comparisons (2026-11-14)

The previous reference was the clean main checkout `be1b27ba0bdbc383190d0543ffb3cfdf9f73e08b`. Its [post-merge CI](https://github.com/carstenartur/Regelsuche/actions/runs/43594627405) passed the seven verification jobs and the aggregate checkout gate. The extractor measured 774320 context units, 9070 more than the previous reference. The allowance is 15000. The [acceptance record](../ai-knowledge/baseline-history/2026-11-14-main-be1b27ba/provenance.json) binds all 286 indexed files to their byte lengths and hashes.

After the later merges, main `03b58d1d` measured 782420 units. The previous reference yielded an increase of 17170 and one [rejected trend](../ai-knowledge/baseline-history/2026-11-14-main-be1b27ba/rejected-trend.json). Applying the pinned extractor to the accepted predecessor gives an increase of 8100 on the same source. The measurement from the rejected run is retained for comparison.

### Accepted main before the November 14 integrations

The earlier baseline was the native v0.1.10 projection of main
`273d850569325fde5f15db9faa45a32c98632ebe`. Its [post-merge CI run](https://github.com/carstenartur/Regelsuche/actions/runs/43551043301)
passed seven verification jobs and the aggregate `ciCheck` against the previous
baseline. The extractor measured 765250 context units, up 14380 from 750870
within the 15000-unit allowance. Its normalized context debt was 14.46.

The [qualification and artifact hashes](../ai-knowledge/baseline-history/2026-11-14-main-273d850/provenance.json)
bind the clean checkout, CI jobs and all 285 verified archive entries.
The pinned extractor's `TrendAnalyzer.snapshot` projected that baseline
from the [accepted complexity report](../ai-knowledge/baseline-history/2026-11-14-main-273d850/accepted-complexity.json).
The [passing check](../ai-knowledge/baseline-history/2026-11-14-main-273d850/accepted-check.json)
records the native gate outcome. The [later run on main](https://github.com/carstenartur/Regelsuche/actions/runs/43557641631)
measured 765970 units, up 15100 against the then-active baseline and 720 from
the qualified predecessor. Its [rejected trend](../ai-knowledge/baseline-history/2026-11-14-main-273d850/rejected-trend.json)
is retained separately.

### Accepted main before the comparison follow-up (2026-11-10)

The previous baseline projected the native v0.1.10 measurements of accepted main
`c267b7fa9c95cc14cfd53b00ceb5d0325908feda`, tree
`a023dbc29cc3402d26b9e42e28d41ab2c3c450f4`. Its
[complete main CI](https://github.com/carstenartur/Regelsuche/actions/runs/42752771954)
passed all seven authorities and `Checkout-local ciCheck` on attempt 1 with the
previous baseline and unchanged limits. Main measured 750870 context units:
+11200 against 739670, within the existing 15000 allowance.

The downloaded `quality-acceptance-evidence` artifact has SHA-256
`852a601689c9757b5924f46bdd6857446e2babed03211edc5a6ed9ac1e537519`;
all 284 indexed files were checked for byte length and SHA-256. Its original
[complexity report](../ai-knowledge/baseline-history/2026-11-10-main-c267b7f/main-native-complexity.json),
[passing check](../ai-knowledge/baseline-history/2026-11-10-main-c267b7f/main-native-check.json),
[index](../ai-knowledge/baseline-history/2026-11-10-main-c267b7f/original-index.json)
and [qualification](../ai-knowledge/baseline-history/2026-11-10-main-c267b7f/source-qualification.json)
are retained. The index binds collection to the clean main tree; the successful
CI establishes execution of the existing gates on that source.

This smaller artifact contains no `metrics-snapshot.json` or `trend.json`.
The [projected baseline](../ai-knowledge/baseline-history/2026-11-10-main-c267b7f/projected-baseline.json)
uses the unchanged `TrendAnalyzer.snapshot` implementation on the native
complexity report, including the measured footprint model v3. It is byte-identical
to the baseline used at that time, SHA-256
`8a5f0e2efa4673872858d6df4e62404724727a018940c6d7f3978d5c4bb5f95a`.
[Provenance](../ai-knowledge/baseline-history/2026-11-10-main-c267b7f/provenance.json)
records exact inputs and the projection. This is neither a downloaded original
snapshot nor a fresh local full extractor run.

The original [rejected combined trend](../ai-knowledge/baseline-history/2026-11-10-main-c267b7f/rejected-combined-trend.json)
remains unchanged: 755575 units, +15905 against the old reference. Only 4705 of
that increase belongs to the comparison follow-up beyond accepted main. These
failed feature values supply no baseline input. Thresholds, selectors, tests,
mathematical code and all existing gates are unchanged; the new feature head
still requires its own complete canonical CI and review.

### Earlier accepted main before typed pair sums (2026-11-07)

The previous baseline was the native v0.1.10 metrics snapshot of main
`902e4ea9710aebcd81c0c172f3154106db2a5c3b`, tree
`abb85fd8918ad2cd0cdb46b3724b4552befe8ecf`. The
[post-merge CI](https://github.com/carstenartur/Regelsuche/actions/runs/42339349253)
passed. A clean source archive passed the native knowledge gate, eight coverage
controls and the 17 hotspot checks against the preceding baseline. It measured
739670 context tokens, an increase of 13360 within the 15000 allowance. The
[previous baseline snapshot](../ai-knowledge/baseline-history/2026-11-10-main-c267b7f/previous-baseline.json) has SHA-256
`d3348a8b61c041ec096b4b0e3753ea0dfcd6f33560ce209b77dfc0c82b6f0c1a`.

### Accepted predecessor for generic numeric domains (2026-10-11)

This historical baseline is the exact generated snapshot of accepted main
`d7ec8b51d164dc58dbb3e9e328af8bb356425b93`, tree
`2e9dd708951e02d68a786aa7871dee7b4a4a6347`, integrated by PR #1068.
Its byte-identical source PR passed
[CI run 38129395135](https://github.com/carstenartur/Regelsuche/actions/runs/38129395135),
and [post-merge main CI](https://github.com/carstenartur/Regelsuche/actions/runs/38134140137)
passed all seven mandatory authorities and `ciCheck` before this promotion.

A fresh clean checkout passed the complete unchanged old-baseline
`aiKnowledgeCheck` with the pinned v0.1.10 extractor: 696505 context tokens,
+13670 against the previous 682835 baseline, below the unchanged 15000 allowance.
All 12 actionable tasks executed, all eight coverage controls passed, and all
17 hotspot checks passed. The original main CI `check.json` and `complexity.json`
are byte-identical to the new native reports. The smaller main
`quality-acceptance-evidence` ZIP was downloaded and its SHA256 matched the
API-reported digest; the larger repository artifact was not downloaded.

[Independent review](../ai-knowledge/baseline-history/2026-10-11-maind7ec8b51/independent-review.md)
approved only this exact predecessor snapshot. The
[accepted snapshot](../ai-knowledge/baseline-history/2026-10-11-maind7ec8b51/metrics-snapshot.json) has
SHA256 `d2146a9f97686f724e49d5e710f46470674b39038b8595eae71dd2983c35c12b`.
[Promotion provenance](../ai-knowledge/baseline-history/2026-10-11-maind7ec8b51/provenance.json)
maps every original measurement artifact into the retained files and portable
archives. The original pre-review provenance, full raw reports, native command
and log, source/tool bindings, CI identities, previous baseline and rejected
PR #1088 reports remain retained.

The [rejected PR #1088 trend](../ai-knowledge/baseline-history/2026-10-11-maind7ec8b51/rejected-pr1088-trend.json)
measured 698325 tokens: +15490 against the previous baseline, exceeding its
allowance by 490, and +1820 against the independently accepted predecessor.
Those rejected values supply no baseline input. Thresholds, selectors, claims,
exceptions, mathematical rules, SDK sources and packaging/build inputs remain
unchanged. The runtime SDK source pin stays at
`2452f1d679bf9e4de7641759efbe30c88af2588c`; the later baseline/evidence revision
requires its own native gate and hosted final-head CI, recorded separately.
This promotion does not qualify the feature, a new SDK binary, or performance.

### Accepted predecessor for checked solver integration (2026-10-09)

The preceding baseline was the exact generated snapshot of accepted main
`ae6b36eae23ed96aed2f0815442ab67634f87065`, tree
`eb052f81047973154d99105273de5851b0c9b9a3`, integrated by PR #1076.
Its byte-identical source PR passed all six authorities and `ciCheck` in
[run 37938454239](https://github.com/carstenartur/Regelsuche/actions/runs/37938454239).
The fresh complete unchanged old-baseline gate measured 682835 tokens, +13970
against 668865, below the unchanged 15000 allowance. Eight coverage controls
and seventeen hotspot checks passed with the pinned v0.1.10 extractor.

[Provenance](../ai-knowledge/baseline-history/2026-10-09-mainae6b36e/provenance.json)
and the [independent review](../ai-knowledge/baseline-history/2026-10-09-mainae6b36e/independent-review.md)
bind the actual plugin policy, selectors, source inventories and raw reports.
That baseline was byte-identical to the
[accepted snapshot](../ai-knowledge/baseline-history/2026-10-09-mainae6b36e/metrics-snapshot.json),
SHA256 `47ca2375967a95763395ad65bad1bff60a0f8e82ed9246684b0ea8dfea2f28ef`.
The first local run included two ignored working notes and was rejected; its
683195-token output and the failed PR #1068 trend remain in the raw-report
archive. Neither supplied baseline values. No threshold, selector, exception
or mathematical proof rule changed. The subsequent
[main CI](https://github.com/carstenartur/Regelsuche/actions/runs/37943010886)
also passed all six authorities and `ciCheck`. Final-head PR CI remains a
separate integration gate; this promotion does not complete P04.

### Accepted predecessor for the checked-proof bridge (2026-10-06)

The earlier proposal in PR #1078 used accepted main `d1270b9b8c652340ed3abb895c1a24bb68cb5899`,
tree `dbfdb5919db388d876779259e4571307ba86a58a`. Its
[main CI](https://github.com/carstenartur/Regelsuche/actions/runs/37475603053)
passed all mandatory authorities and `Checkout-local ciCheck`; the hosted
`ciCheck` ran the complete unchanged old-baseline `aiKnowledgeCheck` with the
pinned v0.1.10 extractor: 682415 estimated tokens, +13550 against 668865, below
the unchanged 15000 allowance; all eight coverage controls and seventeen hotspot
checks passed. The `repository-verification` archive was downloaded and its
SHA256 matched the API-reported digest. The [provenance](../ai-knowledge/baseline-history/2026-10-06-maind1270b9b-provenance.json)
binds source, policy, artifact and copied-report hashes. That proposal is byte-identical
to the [accepted snapshot](../ai-knowledge/baseline-history/2026-10-06-maind1270b9b-metrics-snapshot.json).
The [failed PR trend](../ai-knowledge/baseline-history/2026-10-06-pr-2910951144-rejected-trend.json)
(684225 tokens, +15360 against the former baseline, +1810 against the accepted
predecessor) remains retained and supplies no baseline values. Thresholds,
selectors and exceptions are unchanged.

### Accepted predecessor for the P04 continuation (2026-09-29)

The baseline advances to accepted main `ab48fb0a47eec12cb63c30de253de50d2a72db1c`,
tree `2116235600621cec8410f49903926b36ad7d5365`, after PR #1056. All six hosted
authorities, `Checkout-local ciCheck` and CodeQL passed. A fresh clean standalone
checkout passed the complete unchanged old-baseline `aiKnowledgeCheck` with the
pinned v0.1.10 extractor: 668865 estimated tokens, +13180 against 655685, below
the unchanged 15000 allowance; all eight coverage controls and seventeen hotspot
checks passed. The [provenance and independent review](../ai-knowledge/baseline-history/2026-09-29-mainab48fb0a-provenance.json)
bind source, tool, policy and raw artifact hashes. The baseline is byte-identical
to the [accepted snapshot](../ai-knowledge/baseline-history/2026-09-29-mainab48fb0a-metrics-snapshot.json).
The [failed PR #1055 trend](../ai-knowledge/baseline-history/2026-09-29-pr1055-928d78e5-rejected-trend.json)
remains retained and supplies no baseline values. This updates the accepted
predecessor only; it does not qualify the continuation or complete P04.

### Accepted predecessor for work-replacement P04 (2026-09-28)

The current baseline is the exact generated snapshot of accepted P03 main
`ee6dd6892d899662c857c1f2bc2861e233e68563`, tree
`e1bee2a7712ff95f14b2c3513f565ff6c26bde8b`. Its
[main CI](https://github.com/carstenartur/Regelsuche/actions/runs/36360112301)
(attempt 2) passed all six mandatory authorities and `ciCheck`; CodeQL also passed.
A fresh clean checkout with unchanged extractor v0.1.10 passed all 12 gate tasks:
655685 tokens, +7660 against the former 648025 baseline, below the unchanged
15000 allowance. No threshold, selector, exception or test was changed.

The active file is byte-identical to the
[approved snapshot](../ai-knowledge/baseline-history/2026-09-28-mainee6dd689-metrics-snapshot.json),
SHA256 `2df07604122be739198f4258797379097df6df27e993d706daebd39707ae61c0`.
[Provenance](../ai-knowledge/baseline-history/2026-09-28-mainee6dd689-provenance.json)
and [independent review](../ai-knowledge/baseline-history/2026-09-28-mainee6dd689-independent-review.md)
retain source/tool identities, original commands, successful gate/CI evidence and
all copied evidence hashes. The earlier baseline remains unchanged in history.
The [rejected P04 opening-head trend](../ai-knowledge/baseline-history/2026-09-28-p04-b9fa5833-rejected-trend.json)
(663565 tokens, +15540) and its raw job log/archive identity are retained; they
supplied no baseline values. P04 still requires complete final-head gates, CI
and full independent review. This promotion is no implementation or performance acceptance.

### Accepted predecessor for work-replacement P01 (2026-09-20)

The P01 baseline was the exact freshly generated snapshot of integrated M0
main `7aec9ae0a1619dda98f859d1277ac8b287471423`, tree
`56b841cf704a8db32b1c7c7420faff653087cc0f`, after #1047 and #1048.
Both [the source-head CI](https://github.com/carstenartur/Regelsuche/actions/runs/35514858944)
and [the post-merge main CI](https://github.com/carstenartur/Regelsuche/actions/runs/35517882778)
passed all mandatory authorities and `ciCheck`.

A clean detached reproduction of the complete unchanged `aiKnowledgeCheck`
measured 648025 tokens, an increase of 13580 against the previous 634445 baseline,
below the unchanged 15000 allowance. Eight coverage controls, 17 hotspot checks,
and all absolute/trend limits passed. The clean pinned v0.1.10 extractor and
existing Java 25 initializer retain the original `--release 17` target.

That baseline was a byte-for-byte copy of
[the accepted snapshot](../ai-knowledge/baseline-history/2026-09-20-main7aec9ae0-metrics-snapshot.json),
SHA256 `b2d65ffe25f98ebbac0ca4e493d1b056d8e521cecb034d6b320277eb4e6d584b`.
[Provenance](../ai-knowledge/baseline-history/2026-09-20-main7aec9ae0-provenance.json)
and [independent review](../ai-knowledge/baseline-history/2026-09-20-main7aec9ae0-independent-review.md)
bind clean source/tool identities, unchanged policy, successful CI, commands and
original report hashes. The main artifact ZIP digest is API-reported; its bytes
were not downloaded. Baseline values come from the retained local reproduction.

The initial P01 head `6f59849a63db364b9665ae00f04eca9f09017812` failed the old
trend limit at 650825 tokens (+16380). Its
[rejected report](../ai-knowledge/baseline-history/2026-09-20-p01-6f59849a-rejected-trend.json)
and old baseline remain unchanged. The rejected artifact ZIP was downloaded and
its reported SHA256 independently verified. No failing candidate supplies baseline
values. P01 must pass its own complete current-head CI; this qualification is no
learning or performance acceptance. Thresholds, selectors and exceptions are unchanged.

### Accepted predecessor for learned-schema v2 (2026-09-20)

For learned-schema v2, the baseline advanced to the freshly generated snapshot of accepted main
`81ea9ac7dc46f310f68a071b944ea144e581c2ed` after merged PR #1038, tree
`e3a2a95e4ecd91cc9d0091995bbe66cc2c7f67b2`. Its PR and post-merge CI runs
[35474041553](https://github.com/carstenartur/Regelsuche/actions/runs/35474041553)
and [35476125115](https://github.com/carstenartur/Regelsuche/actions/runs/35476125115)
passed all required authorities and `ciCheck` before this feature was evaluated.

A clean detached checkout of that predecessor passed the complete unchanged
`aiKnowledgeCheck` against the previous 622215-token baseline: 634445 tokens,
increase 12230 below the unchanged 15000 allowance. The run also passed eight
coverage controls, 17 method-hotspot checks and all other absolute/trend limits.
The pinned v0.1.10 extractor source was clean. A retained local initializer
selected JDK 25 to compile it with its unchanged `--release 17` target; it altered
no source, estimator weight, selector, threshold or verification task.

That baseline was a byte-for-byte copy of
[that generated snapshot](../ai-knowledge/baseline-history/2026-09-20-main81ea9ac7-metrics-snapshot.json),
SHA256 `2928c6dabe73e410e8c50e584cfaa21b9e17eabc59f53c85985ea233fb217af9`.
The [qualification and measurement provenance](../ai-knowledge/baseline-history/2026-09-20-main81ea9ac7-provenance.json)
binds the source/tree, successful CI jobs, commands, toolchain and original report
hashes. Independent review verified these identities and unchanged policy before
the copy. The old baseline and rejected feature report (645285 tokens, +23070
against the old allowance) remain retained. No failing feature supplies baseline
values. The feature must still pass its own complete CI; this update supplies no
performance or learning acceptance.

### Earlier accepted predecessors

The committed `ai-knowledge/complexity-baseline.json` advances only to an
independently qualified predecessor. It is not generated from the branch under
test. For the stacked issue integration, the accepted predecessor is `main`
`5041f9d88047b3eb4171c8bfc4017c30355f6a52`, tree
`536dabef711c38a8f435dd75df4c332583abd929`, after the actual merge of PR #998.

PR #998 head `d57fc92d97c5535cbd19cbaa37b1b5b1c02f943d` and the qualified local
commit `34aba8461edb39d4c983d5d087a1820081aada87` have exactly that tree.
[CI run 34739558945](https://github.com/carstenartur/Regelsuche/actions/runs/34739558945)
passed Gradle, Maven/product/Docker, isolated JMH, external polynomial comparison,
isolated SymPy and the converged `ciCheck`. The retained
`repository-verification` artifact is `10312069326`, API-reported ZIP SHA-256
`d6a27a818f34422dffdc4314cc921858225ac54e1f0c6641032acff869833bdb`.
The source PR merge state, qualification statuses and artifact metadata were
independently rechecked through the GitHub API; the ZIP was not downloaded or
hashed for this update. Four manual JMH study jobs and report publication were
skipped; no frozen study was rerun.

The baseline values come exclusively from a fresh local reproduction directly on
accepted main `5041f9d88047b3eb4171c8bfc4017c30355f6a52`, with source and extractor
checkouts independently checked clean before and after the run. The complete
`aiKnowledgeCheck` passed in 15 seconds with the pinned 0.1.10 extractor,
including artifact validation, all eight
coverage controls and all 16 method-hotspot checks. Its generated snapshot measured:

- `estimatedContextTokens = 582555`
- `conceptRadius = 88`
- `dependencyRadius = 273`
- legacy diagnostic `aiCognitiveDebt = 652.9491176470588`
- normalized `aiContextDebt = 16.59`
- `contextDebtModelVersion = context-footprint-v3`

That historical baseline was a byte-for-byte copy of the retained
[predecessor snapshot](../ai-knowledge/baseline-history/5041f9d880-metrics-snapshot.json),
SHA-256 `c0336da54cdaec31497aaf47214fafed518beaccde32699811116ba76ffa8881`.
The [provenance record](../ai-knowledge/baseline-history/2026-09-13-main5041-provenance.json)
binds the source/tree, complete CI job IDs, extractor commit, command, source and
report hashes, and unchanged policy. The source qualification and fresh execution
receipts, generated predecessor check, trend and full local command log are
retained alongside it. The baseline changed only after this clean measurement
passed. All earlier baseline history remains unchanged.

The later rejected runtime diagnostic measured 593755 estimated tokens against
the former 575305 baseline: its 18450 increase exceeded the unchanged 15000
allowance. Its [original rejected trend](../ai-knowledge/baseline-history/2026-09-13-e145-context-rejected-trend.json),
snapshot, check and command log remain unchanged. The coordinating agent's
working context associates this run with `e14542a9a29a47f82b6f630992e949e7bdcd66a2`,
but no separate cleanliness receipt or exact complete shell invocation was
retained. The provenance therefore records `UNVERIFIED_WORKING_TREE_ASSOCIATION`:
that commit/tree is context, not an independently established source binding.
The rejected output neither supplies a baseline nor qualifies a later candidate.

An earlier runtime WIP measured 586895 estimated tokens against the former 569510
baseline: its 17385 increase exceeded the unchanged 15000 allowance. Its
[rejected trend](../ai-knowledge/baseline-history/2026-09-13-runtime-wip-rejected-trend.json),
snapshot, check and command log remain diagnostic evidence. No exact source or
tree binding was retained for that WIP, and the working tree changed afterward;
these outputs do not qualify any current commit. No integration or runtime
candidate value is used as a baseline.

The accepted predecessor was measured with its own committed capability selectors,
coverage policy and quality settings. These remain unchanged by this update.
The pinned estimator still uses the same inventory weights; independently
recounting the local predecessor reports gives 582555 estimated tokens. The
accepted predecessor itself passed against the former 575305 baseline with an
increase of 7250 tokens; no failed candidate establishes its own comparison point.

Version 0.1.10 writes the model id into trend snapshots, so
`maxCognitiveDebtIncrease` compares normalized debt when baseline and current model
versions match. If the current snapshot is normalized but the baseline model is
missing or different, only that debt trend is reported as non-comparable and
skipped; the absolute normalized debt gate and all other configured gates remain
active. Callers without a normalized current model retain the legacy
`aiCognitiveDebt` trend path.

The policy limits remain unchanged: the context-debt increase allowance is 10
units, the context-token allowance is 15000, the concept-radius allowance is 3,
and the absolute normalized debt and method-hotspot rules remain in force.
Future advances require an independently qualified predecessor and retained
provenance; a failing candidate cannot establish its own baseline.

## Historical baseline provenance

The learned-rule scheduling work was originally measured against independently
green `main` `b0fc59be75d61e5005a924d853780225651eb334` after #951/#952. CI run
`34372553674` passed all authorities; its retained baseline artifact was
`10113524705` (ZIP SHA-256
`4a61caa5d905a54d4031dc3bfef8d32caa01d55758a5a9623005678ac8856635`).

For the subsequent stacked PRs, the baseline advanced to independently green
PR #959 commit `fcdd944e6be39b314fb95ab59f5c3cc78c45188d`. CI run `34382028949`
passed Gradle, Maven/product/Docker, isolated JMH, isolated SymPy and aggregate
`ciCheck`; the retained artifact was `10117201394` (ZIP SHA-256
`2754c79aab3b10eb564da5c73211bc6de10c9084687765c493bc95c9980a1864`).

The 0.1.10 metric-correctness migration then used independently green `main`
`f19ee628fca26bd00950195a38eb940de9293ebc`, CI run `34581736197`, retained
`repository-verification` artifact `10192975764`. It was the accepted `main`
state before adopting corrected normalized trend semantics. That snapshot had
`estimatedContextTokens = 557650`, `conceptRadius = 85`, `dependencyRadius = 254`,
legacy `aiCognitiveDebt = 625.5676470588235`, normalized `aiContextDebt = 17.11`
and `contextDebtModelVersion = context-footprint-v3`. The normalized value came
from the artifact's schema-v3 `normalizedContextDebt` field.

The exact formerly active baseline bytes remain in
[baseline history](../ai-knowledge/baseline-history/f19ee628-complexity-baseline.json),
SHA-256 `aea907748449180b6b681f5003a435048cc912bed67482f729ca007eb2c2b096`.
The next accepted predecessor was `main`
`85f6ce9301a7f3dcf3243937d5b463113c61846f`, tree
`b8fd2b7721f19049d11d703cbe1c53869b3ccd7b`, identical to PR #985 head
`cbd7ccbda90c7f3e3c96a02ca2830938fcb5a75a`. Its six authorities passed
[CI run 34726058558](https://github.com/carstenartur/Regelsuche/actions/runs/34726058558).
The [85f6 snapshot](../ai-knowledge/baseline-history/85f6ce9301-metrics-snapshot.json)
and [original provenance](../ai-knowledge/baseline-history/2026-09-13-main85f6-provenance.json)
remain unchanged, including the 569510-token baseline, report hashes and original
comparison limits.

The subsequent accepted predecessor was `main`
`e673c6c89bca00f68ca47b8df2a7d20203089b29`, tree
`3a271c01bac233259e47a46c78f6f49274804eb7`, identical to PR #997 head
`067619282e4ac4778900c893170283e2a9e57260`. Its six authorities passed
[CI run 34737310998](https://github.com/carstenartur/Regelsuche/actions/runs/34737310998).
The [575305-token snapshot](../ai-knowledge/baseline-history/e673c6c89b-metrics-snapshot.json),
SHA-256 `4e849da270eb40f15934c9a6ad5493aeb3e5c40efc86bedfdeb4c80687af0f24`,
and [original provenance](../ai-knowledge/baseline-history/2026-09-13-maine673-provenance.json)
remain unchanged with their source qualification, reports, command log and
original evidence boundaries.

The original integration measurement at
`f2da31bf6652bbfaffd9541b7deb79ecc6327499` remains
[rejected evidence](../ai-knowledge/baseline-history/f2da31bf66-rejected-trend.json):
575165 estimated tokens exceeded the former 557650 baseline by 17515, above the
unchanged 15000 allowance. Compared with the then-accepted 85f6 predecessor, the
same measurement increased by 5655 tokens and one concept-radius unit. The #984
selector expansion also affected normalized debt, so that historical comparison
was not a controlled comparison of source changes alone. None of these rejected
integration values established a baseline. All older measurements retain their
original source and qualification identities; the active successor is the
independently accepted P03 snapshot described above.

## Accepted predecessor after SDK integration — 21 November 2026

The successful [push/main CI 44919811586](https://github.com/carstenartur/Regelsuche/actions/runs/44919811586)
at `16a345b3d8b2cb71c50e3ad9025ea30fb8dbc4e3`, tree
`2ba7db35d10d57c45298a99df50e70860ea01d15`, measured 847,010
estimated context units and concept radius 117 with the pinned 0.1.10 extractor.
Against the prior independently accepted 832,860 / 115 baseline this is
**+14,150 / +2** — within the **unchanged +15,000 / +3** limits.
The original ZIP digest and all 290 indexed source/report SHA-256 and byte-length
checks are recorded under [the new accepted-main provenance](../ai-knowledge/baseline-history/2026-11-21-main-16a345b3/provenance.json).
The byte-identical numerical projection is retained in
[metrics-snapshot.json](../ai-knowledge/baseline-history/2026-11-21-main-16a345b3/metrics-snapshot.json).
The original archive stays with GitHub Actions. No failing feature branch or
unqualified local measurement was promoted. In particular, this does **not**
qualify the pending Maven study/quality collector, which still requires its
own full exact-head CI and review.
