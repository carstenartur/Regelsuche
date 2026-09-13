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

The committed `ai-knowledge/complexity-baseline.json` advances only to an
independently qualified predecessor. It is not generated from the branch under
test. For the stacked issue integration, the accepted predecessor is `main`
`e673c6c89bca00f68ca47b8df2a7d20203089b29`, tree
`3a271c01bac233259e47a46c78f6f49274804eb7`.

PR #997 head `067619282e4ac4778900c893170283e2a9e57260` has exactly that tree.
[CI run 34737310998](https://github.com/carstenartur/Regelsuche/actions/runs/34737310998)
passed Gradle, Maven/product/Docker, isolated JMH, external polynomial comparison,
isolated SymPy and the converged `ciCheck`. The retained
`repository-verification` artifact is `10311229494`, API-reported ZIP SHA-256
`2352c426bf8101becb4f7f21a8cf98f36b705fbaf15090c25e593db087cf44ea`.
The source PR merge state, qualification statuses and artifact metadata were
independently rechecked through the GitHub API; the ZIP was not downloaded or
hashed for this update. The separate report-publication job was skipped.

The baseline values come exclusively from a fresh, clean local reproduction of
that identical predecessor tree at local commit
`1dad1683e9b08c6fb2626d687b978f2f0456ef70`. The complete `aiKnowledgeCheck` passed
with the pinned 0.1.10 extractor, including artifact validation, all eight
coverage controls and all 16 method-hotspot checks. Its generated snapshot measured:

- `estimatedContextTokens = 575305`
- `conceptRadius = 87`
- `dependencyRadius = 271`
- legacy diagnostic `aiCognitiveDebt = 644.440294117647`
- normalized `aiContextDebt = 16.72`
- `contextDebtModelVersion = context-footprint-v3`

The active baseline is a byte-for-byte copy of the retained
[predecessor snapshot](../ai-knowledge/baseline-history/e673c6c89b-metrics-snapshot.json),
SHA-256 `4e849da270eb40f15934c9a6ad5493aeb3e5c40efc86bedfdeb4c80687af0f24`.
The [provenance record](../ai-knowledge/baseline-history/2026-09-13-maine673-provenance.json)
binds the source/tree, complete CI job IDs, extractor commit, command, source and
report hashes, and unchanged policy. The source qualification receipt, generated
predecessor check, trend and local command log are retained alongside it. The
update independently checked these retained bytes and source identities; it did
not run another Gradle build. All earlier baseline history remains unchanged.

An earlier runtime WIP measured 586895 estimated tokens against the former 569510
baseline: its 17385 increase exceeded the unchanged 15000 allowance. Its
[rejected trend](../ai-knowledge/baseline-history/2026-09-13-runtime-wip-rejected-trend.json),
snapshot, check and command log remain diagnostic evidence. No exact source or
tree binding was retained for that WIP, and the working tree changed afterward;
these outputs do not qualify any current commit. No integration or runtime
candidate value is used as a baseline.

The accepted predecessor was measured with its own committed capability selectors,
coverage policy and quality settings. These match the baseline-update parent
`ce8939510d500b0cc09ea2d87ea754c58fcd45ad` byte for byte and remain unchanged.
The pinned estimator still uses the same inventory weights; independently
recounting the local predecessor reports gives 575305 estimated tokens.

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
independently accepted `e673c6c89b...` snapshot above.
