# AI Knowledge Artifacts

This directory documents the Regelsuche integration with the `org.aiknowledge.extractor` Gradle plugin.

The canonical generated artifacts are produced under `build/ai-knowledge/` by:

```bash
./gradlew generateAiKnowledgeIndex analyzeAiComplexity optimizeAiKnowledge benchmarkAiKnowledge
```

In CI, `.github/workflows/ai-knowledge.yml` checks out `carstenartur/ai-knowledge-extractor` next to this repository, runs the extractor via the existing composite-build configuration, and uploads `build/ai-knowledge/**` as the `ai-knowledge-index` workflow artifact.

`publishAiKnowledgeIndex` can additionally copy generated files into this directory for manual review. Bulk JSON snapshots are ignored by default to avoid noisy diffs; promote selected snapshots deliberately when they are meant to become documentation.

Seed files live in `ai-knowledge/` and use the documented plugin seed format:

- `ai-knowledge/capabilities.seed.yaml`
- `ai-knowledge/claims.seed.yaml`

The extractor also scans project evidence from Regelsuche itself, including generated discovery evidence, Java/JMH sources, Markdown docs, build metadata, dependencies and GitHub workflow metadata when supported by the plugin version.
