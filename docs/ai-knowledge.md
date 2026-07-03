# AI knowledge index

Regelsuche can consume the separate `ai-knowledge-extractor` project as a local Gradle composite build.

## Local setup

Clone both repositories next to each other:

```text
workspace/
  Regelsuche/
  ai-knowledge-extractor/
```

The Regelsuche `settings.gradle` checks for `../ai-knowledge-extractor` and includes it as a composite build when present.

## Generate the index

```bash
./gradlew generateAiKnowledgeIndex
```

The generated files are written to:

```text
build/ai-knowledge/
```

Important artifacts:

```text
index.json
modules.json
classes.json
tests.json
docs.json
capabilities.json
dependencies.json
claims.json
```

## Further reports

```bash
./gradlew analyzeAiComplexity
./gradlew optimizeAiKnowledge
./gradlew benchmarkAiKnowledge
./gradlew checkAiKnowledgeIndex
./gradlew publishAiKnowledgeIndex
```

`checkAiKnowledgeIndex` evaluates rule-bearing claim seeds from `ai-knowledge/claims.seed.yaml`. Claims marked with `severity: error` fail the build; `warning` claims remain advisory.

`publishAiKnowledgeIndex` copies the generated snapshot to `docs/ai-knowledge/` when a committed documentation snapshot is desired.

The extractor is deterministic and does not require external LLM or SaaS calls.
