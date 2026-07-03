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
evidence.json
review-context.md
context-packs/index.json
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

## CI workflow

The `.github/workflows/ai-knowledge.yml` workflow is triggered on changes to Java sources (`**/src/main/java/**`, `**/src/test/java/**`, `**/src/jmh/java/**`), seed files (`ai-knowledge/**`), generated discovery docs (`docs/generated/discovery/**`), build scripts (`**/build.gradle`, `settings.gradle`), and the workflow file itself.

It performs the following checks in order:

1. **Generate** — runs `generateAiKnowledgeIndex analyzeAiComplexity optimizeAiKnowledge benchmarkAiKnowledge`.
2. **Check** — runs `checkAiKnowledgeIndex`; fails the build for any claim with `severity: error`.
3. **Validate file presence** — asserts that all required artifact files are present in `build/ai-knowledge/`.
4. **Validate quality** — checks that `evidence.json` contains entries, `review-context.md` is non-trivial, and context packs exist for major capabilities.

Two artifacts are uploaded on every run:

- `ai-knowledge-index` — the complete `build/ai-knowledge/**` output.
- `ai-knowledge-review-reports` — `review-context.md`, `context-packs/`, and the check logs for human review.
