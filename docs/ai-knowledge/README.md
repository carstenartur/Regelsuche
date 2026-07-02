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

## Capability seed structure

Each capability entry in `ai-knowledge/capabilities.seed.yaml` uses the following fields:

| Field | Purpose |
|---|---|
| `id` | Unique capability identifier (kebab-case) |
| `label` | Short human-readable name |
| `description` | Prose description of what the capability does |
| `modules` | All Gradle modules that contribute to this capability |
| `ownerModules` | The primary module(s) that own the capability's domain logic |
| `packages` | Java package names that belong to this capability |
| `typePatterns` | Glob patterns matching Java type names in this capability |
| `testPatterns` | Glob patterns matching test class names for this capability |
| `docPatterns` | File glob patterns for documentation belonging to this capability |
| `evidenceTypes` | Evidence artefact types produced by or linked to this capability |
| `jmhPatterns` | File glob patterns for JMH benchmark sources (performance-sensitive capabilities only) |
| `tags` | Free-form tags used for indexing and filtering |

## Capabilities

### rewrite-search

Term rewrite search over algebraic expressions using configurable strategies (BFS, DFS, A\*, beam search, MCTS).

- **Owner module**: `regelsuche-search`
- **Core packages**: `de.regelsuche.search`, `de.regelsuche.scoring`, `de.regelsuche.moves`, `de.regelsuche.moves.search`
- **Evidence**: `discovery-evidence`
- **JMH benchmarks**: `app/src/jmh` (`CoreBenchmarks` — rule index and transformation engine hot paths)
- **Docs**: `docs/search-strategies.md`, `docs/search-intelligence.md`, `docs/search-intelligence-roadmap.md`, `docs/search-space-analytics.md`, `docs/rewrite-rules.md`

### equality-saturation

E-graph construction and equality saturation for simultaneous exploration of all equivalent expression forms.

- **Owner module**: `regelsuche-egraph`
- **Core packages**: `de.regelsuche.egraph`
- **Evidence**: `discovery-evidence`
- **JMH benchmarks**: `app/src/jmh` (`CoreBenchmarks` — e-graph hot path)
- **Docs**: `docs/equality-saturation.md`

### hypothesis-mining

Candidate rewrite-rule mining from successful transformation paths via anti-unification and pattern generalisation.

- **Owner module**: `regelsuche-learning`
- **Core packages**: `de.regelsuche.mining`, `de.regelsuche.moves.hypothesis`
- **Evidence**: `hypothesis-evidence`, `discovery-evidence`
- **Docs**: `docs/hypothesis-mining.md`

### counterexample-search

Validation of algebraic identities and candidate rules by searching for numerical or symbolic counterexamples.

- **Owner module**: `regelsuche-validation`
- **Core packages**: `de.regelsuche.validation`, `de.regelsuche.equivalence`, `de.regelsuche.math.algorithms.equivalence`
- **Evidence**: `validation-evidence`
- **Docs**: `docs/rule-validation.md`

### proof-workflows

Proof-oriented workflows: Knuth-Bendix critical-pair completion, Groebner basis reduction, PSLQ numerical relation search.

- **Owner module**: `regelsuche-math-algorithms`
- **Core packages**: `de.regelsuche.math.algorithms`, `de.regelsuche.math.algorithms.completion`, `de.regelsuche.proof`
- **Evidence**: `proof-evidence`, `discovery-evidence`
- **Docs**: `docs/proof-bridge.md`, `docs/proof-workbench.md`, `docs/prover-execution.md`, `docs/mathematical-algorithms.md`

### discovery

Recording and replay of discovered transformation paths with full reproducibility metadata for scientific benchmarking.

- **Owner module**: `regelsuche-discovery`
- **Core packages**: `de.regelsuche.discovery`, `de.regelsuche.benchmark`, `de.regelsuche.benchmarks`
- **Evidence**: `discovery-evidence`
- **JMH benchmarks**: `regelsuche-benchmarks/src` (discovery benchmark suite)
- **Generated evidence**: `docs/generated/discovery/**`, `docs/demo-gallery.md`
- **Docs**: `docs/rule-discovery.md`, `docs/discovery-engine.md`, `docs/discovery-benchmarks.md`, `docs/experiment-runner.md`, `docs/scientific-reproducibility.md`

### persistence

Checkpointable search-job persistence via a port/adapter architecture (JSON in-memory and Hibernate/PostgreSQL adapters).

- **Owner module**: `regelsuche-persistence`
- **Core packages**: `de.regelsuche.persistence`, `de.regelsuche.checkpoint`
- **Evidence**: `persistence-evidence`
- **Docs**: `docs/checkpointing.md`, `docs/storage-architecture.md`, `docs/persistence.md`

### web-workbench

Browser-based workbench for interactive rule editing, search execution, proof review and experiment management via a lightweight HTTP API.

- **Owner module**: `app`
- **Core packages**: `de.regelsuche.web`, `de.regelsuche.cli`, `de.regelsuche.api`, `de.regelsuche.plugin`
- **Evidence**: `ui-evidence`
- **Docs**: `docs/web-workbench.md`, `docs/web-ui-user-guide.md`, `docs/web-workbench-security.md`, `docs/job-control.md`, `docs/plugin-api.md`, `docs/plugins.md`

## Evidence type reference

| Evidence type | Produced by | Linked capabilities |
|---|---|---|
| `discovery-evidence` | Discovery campaigns, gallery generation | `discovery`, `rewrite-search`, `equality-saturation`, `proof-workflows` |
| `hypothesis-evidence` | Hypothesis mining runs | `hypothesis-mining` |
| `validation-evidence` | Counterexample search, rule quality dashboard | `counterexample-search` |
| `proof-evidence` | Proof job execution, proof bridge | `proof-workflows` |
| `persistence-evidence` | Checkpoint / restore integration tests | `persistence` |
| `ui-evidence` | Browser E2E tests, Playwright flows | `web-workbench` |
