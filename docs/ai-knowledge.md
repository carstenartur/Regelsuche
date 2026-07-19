# AI knowledge index

Regelsuche can consume the separate `ai-knowledge-extractor` project either as
a local Gradle composite build or as the published Gradle plugin. The extractor
is deterministic and does not require an external LLM or SaaS call.

## Local setup

For composite-build development, clone both repositories next to each other:

```text
workspace/
  Regelsuche/
  ai-knowledge-extractor/
```

The Regelsuche `settings.gradle` checks for `../ai-knowledge-extractor` and
includes it as a composite build when present. Enable the extractor explicitly
so an ordinary build that does not need this external plugin remains lean:

```bash
./gradlew \
  --no-configuration-cache \
  -PenableAiKnowledgeExtractor=true \
  aiKnowledgeCheck
```

The equivalent environment-variable form used by central CI is:

```bash
AI_KNOWLEDGE_EXTRACTOR_ENABLED=true \
  ./gradlew --no-configuration-cache aiKnowledgeCheck
```

The published plugin is hosted in GitHub Packages. A build that does not use the
local composite therefore needs package-read credentials through
`GITHUB_ACTOR` and `GITHUB_TOKEN`.

`--no-configuration-cache` is explicit because extractor version 0.1.7 is not
yet part of the repository's configuration-cache contract. This exception is
limited to the dedicated AI-knowledge lifecycle; the normal `test`, `check` and
`fullCheck` lifecycles continue to exercise the configuration cache.

## Authoritative verification lifecycle

`aiKnowledgeCheck` owns the complete executable contract:

1. generate the index;
2. analyze complexity;
3. produce optimization and benchmark reports;
4. run the extractor's claim checks;
5. independently verify required files and schema-v3 quality through
   `scripts/verify-ai-knowledge-artifacts.py`.

The independent verifier rejects missing or empty files, duplicate JSON fields,
empty evidence, shallow Java extraction without method facts, unresolved
capability references, unmeasured context footprints, inconsistent normalized
context debt and invalid capability/sample counts.

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

For focused development, the underlying tasks remain available when the
extractor is enabled:

```bash
./gradlew --no-configuration-cache -PenableAiKnowledgeExtractor=true generateAiKnowledgeIndex
./gradlew --no-configuration-cache -PenableAiKnowledgeExtractor=true analyzeAiComplexity
./gradlew --no-configuration-cache -PenableAiKnowledgeExtractor=true optimizeAiKnowledge
./gradlew --no-configuration-cache -PenableAiKnowledgeExtractor=true benchmarkAiKnowledge
./gradlew --no-configuration-cache -PenableAiKnowledgeExtractor=true checkAiKnowledgeIndex
./gradlew --no-configuration-cache -PenableAiKnowledgeExtractor=true publishAiKnowledgeIndex
```

`checkAiKnowledgeIndex` evaluates rule-bearing claim seeds from
`ai-knowledge/claims.seed.yaml`. Claims marked with `severity: error` fail the
build; `warning` claims remain advisory. `publishAiKnowledgeIndex` copies a
generated snapshot to `docs/ai-knowledge/` when a committed documentation
snapshot is desired.

## CI boundary

AI-knowledge pass/fail semantics no longer live in a dedicated GitHub Actions
workflow. The central CI workflow only grants package-read access, invokes the
checkout-owned `aiKnowledgeCheck` task and retains `build/ai-knowledge/**` in the
normal repository-verification artifact. The same command and verifier are
therefore available outside GitHub Actions.
