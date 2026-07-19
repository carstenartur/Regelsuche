# AI knowledge index

Regelsuche can consume the separate `ai-knowledge-extractor` project either as
a local Gradle composite build or from GitHub Packages.

## Local setup

Clone both repositories next to each other:

```text
workspace/
  Regelsuche/
  ai-knowledge-extractor/
```

`settings.gradle` detects `../ai-knowledge-extractor` and includes it as a
composite build when present. In package mode, provide `GITHUB_ACTOR` and a
`GITHUB_TOKEN` with package-read access.

## Complete verification lifecycle

Run generation, complexity analysis, optimization, benchmarking, claim checks
and independent artifact verification through one checkout-local Gradle task:

```bash
AI_KNOWLEDGE_EXTRACTOR_ENABLED=true \
  ./gradlew --no-configuration-cache aiKnowledgeCheck
```

The extractor currently requires configuration-cache opt-out. All verification
semantics nevertheless live in the checkout rather than in GitHub Actions.

`aiKnowledgeCheck` executes:

1. `generateAiKnowledgeIndex`;
2. `analyzeAiComplexity`;
3. `optimizeAiKnowledge`;
4. `benchmarkAiKnowledge`;
5. `checkAiKnowledgeIndex`;
6. `verifyAiKnowledgeArtifacts`.

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

The component tasks remain available for focused development:

```bash
./gradlew generateAiKnowledgeIndex
./gradlew analyzeAiComplexity
./gradlew optimizeAiKnowledge
./gradlew benchmarkAiKnowledge
./gradlew checkAiKnowledgeIndex
./gradlew verifyAiKnowledgeArtifacts
./gradlew publishAiKnowledgeIndex
```

`checkAiKnowledgeIndex` evaluates rule-bearing claim seeds from
`ai-knowledge/claims.seed.yaml`. Claims marked with `severity: error` fail the
build; `warning` claims remain advisory.

`publishAiKnowledgeIndex` copies the generated snapshot to
`docs/ai-knowledge/` when a committed documentation snapshot is desired. The
extractor is deterministic and does not require external LLM or SaaS calls.

## CI boundary

`.github/workflows/ai-knowledge.yml` remains separate only because GitHub
Packages authentication is an execution boundary. The workflow provisions Java
and Gradle, invokes `aiKnowledgeCheck`, and uploads the generated artifacts. It
contains no artifact expectations, inline interpreters or alternative test
graph. The same command can be run from any ordinary checkout with either the
local composite build or package credentials.
