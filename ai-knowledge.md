# AI knowledge index

Regelsuche consumes the released `org.aiknowledge.extractor` Gradle plugin from
the `carstenartur/ai-knowledge-extractor` GitHub Packages repository. The
consumer version is pinned once in `gradle.properties`:

```properties
aiKnowledgeExtractorVersion=0.1.7
```

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

`aiKnowledgeCheck` executes:

1. `generateAiKnowledgeIndex`;
2. `analyzeAiComplexity`;
3. `optimizeAiKnowledge`;
4. `benchmarkAiKnowledge`;
5. `checkAiKnowledgeIndex`;
6. `verifyAiKnowledgeArtifacts`.

The extractor currently requires configuration-cache opt-out. All pass/fail
semantics nevertheless live in the checkout rather than in GitHub Actions.

The final task runs `scripts/verify-ai-knowledge-artifacts.py`. It rejects
missing or empty files, malformed or duplicate-field JSON, empty evidence,
missing context packs, unresolved capability references and drift from the
measured schema-v3 context-footprint contract. A missing `methodFacts` section
remains an explicit warning rather than a build failure.

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

Root-project aliases preserve the familiar commands while delegating to the
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
`AI_KNOWLEDGE_EXTRACTOR_ENABLED=true`. The Gradle task then includes
`aiKnowledgeCheck` in the same graph as the rest of the repository verification.
Generated files are retained by the generic repository-verification artifact.

The exact GitHub execution can be reproduced locally with the released package
command above, or without package credentials by selecting the explicit local
composite build. Authentication changes how Gradle resolves the plugin; it does
not change which tasks, assertions or artifact contracts are executed.
