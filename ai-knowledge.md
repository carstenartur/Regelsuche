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

The committed `ai-knowledge/complexity-baseline.json` is not chosen from the
branch under test. For the 0.1.10 migration it is derived from independently
green Regelsuche `main` commit
`f19ee628fca26bd00950195a38eb940de9293ebc`, CI run `34581736197`, retained
`repository-verification` artifact `10192975764`.

That accepted source revision measured:

- `estimatedContextTokens = 557650`
- `conceptRadius = 85`
- `dependencyRadius = 254`
- legacy diagnostic `aiCognitiveDebt = 625.5676470588235`
- normalized `aiContextDebt = 17.11`
- `contextDebtModelVersion = context-footprint-v3`

The normalized value comes from the schema-v3 context footprint in the retained
artifact (`normalizedContextDebt = 17.11`). Version 0.1.10 writes the model id
into trend snapshots, so `maxCognitiveDebtIncrease` compares normalized debt
when baseline and current model versions match. If the current snapshot is
normalized but the baseline model is missing or different, only that debt trend
is reported as non-comparable and skipped; the absolute normalized debt gate and
all other configured gates remain active. For compatibility callers that do not
expose a normalized current context-debt model, the extractor retains the legacy
`aiCognitiveDebt` trend path rather than silently treating it as normalized debt.

The policy limits are deliberately unchanged by this migration: the context-debt
increase allowance remains 10 units, the context-token allowance remains 15000,
the concept-radius allowance remains 3, and the method-hotspot rules remain in
force. This is a metric-correctness migration, not a relaxation made to pass a
particular pull request.

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

Those historical measurements remain useful provenance, but the active
context-debt trend baseline is now the independently green `f19ee628...` snapshot
above because it is the last accepted `main` state before adopting the corrected
normalized trend semantics.
