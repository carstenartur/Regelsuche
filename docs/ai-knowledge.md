# AI knowledge index

Regelsuche consumes the released `org.aiknowledge.extractor` Gradle plugin from
the `carstenartur/ai-knowledge-extractor` GitHub Packages repository. The
consumer version is pinned once in `gradle.properties`:

```properties
aiKnowledgeExtractorVersion=0.1.9
```

Version 0.1.9 prunes generated `build/` and `target/` subtrees before repository
inventory traversal. This keeps parallel verification deterministic while
preserving the complete analysis of checkout-owned source and documentation.

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

The generated files are written to:

```text
build/ai-knowledge/
```

Important artifacts include:

```text
index.json
modules.json
classes.json
tests.json
docs.json
capabilities.json
dependencies.json
claims.json
evidence.json
complexity.json
review-context.md
context-packs/index.json
```

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

## Baseline for learned-rule scheduling

PRs 1–6 were measured against independently green main
`b0fc59be75d61e5005a924d853780225651eb334` after #951/#952. Its complete
[CI run 34372553674](https://github.com/carstenartur/Regelsuche/actions/runs/34372553674)
passed all authorities; the original baseline came from artifact `10113524705`
(ZIP SHA-256 `4a61caa5d905a54d4031dc3bfef8d32caa01d55758a5a9623005678ac8856635`).

For the subsequent stacked PRs, the baseline advances to the independently
fully green preceding PR #959 commit
`fcdd944e6be39b314fb95ab59f5c3cc78c45188d`.
[CI run 34382028949](https://github.com/carstenartur/Regelsuche/actions/runs/34382028949)
passed Gradle, Maven/product/Docker, isolated JMH, isolated SymPy and aggregate
ciCheck before this baseline change. The committed values are copied unchanged
from `current` in `build/ai-knowledge/trend.json`, artifact `10117201394`
(ZIP SHA-256 `2754c79aab3b10eb564da5c73211bc6de10c9084687765c493bc95c9980a1864`).

This preserves the incremental quality check for the eight dependent PRs:
the predecessor passed against the original main baseline, and the new steps
are measured against that verified predecessor. No metric from a failing
landmark/evaluation head is used. The 10-unit cognitive-debt, 15000-token,
concept-radius and method-hotspot limits are unchanged. No hotspot exception
is added. The `public-java-sdk` capability continues to link its public entry
points, contract tests and tutorials.
