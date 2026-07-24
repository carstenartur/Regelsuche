# AI Knowledge Artifacts

This directory documents the Regelsuche integration with the
`org.aiknowledge.extractor` Gradle plugin.

The canonical generated artifacts are produced under `build/ai-knowledge/` by:

```bash
./gradlew generateAiKnowledgeIndex analyzeAiComplexity \
  optimizeAiKnowledge benchmarkAiKnowledge
```

The complete contract is:

```bash
AI_KNOWLEDGE_EXTRACTOR_ENABLED=true \
  ./gradlew --no-configuration-cache aiKnowledgeCheck
```

The repository-wide `ciCheck` task includes the same lifecycle whenever the
enablement flag is set. GitHub invokes that one checkout task and retains
`build/ai-knowledge/**` inside the generic repository-verification artifact;
there is no AI-Knowledge-specific workflow or alternative assertion graph.

`publishAiKnowledgeIndex` can additionally copy generated files into this
directory for manual review. Bulk JSON snapshots are ignored by default to
avoid noisy diffs; promote selected snapshots deliberately when they are meant
to become documentation.

Seed files live in `ai-knowledge/` and use the documented plugin seed format:

- `ai-knowledge/capabilities.seed.yaml`
- `ai-knowledge/claims.seed.yaml`

The extractor also scans project evidence from Regelsuche itself, including
generated discovery evidence, Java/JMH sources, Markdown docs, build metadata,
dependencies and workflow metadata supported by the pinned plugin version.

## Claim seed structure

Regelsuche uses rule-bearing claim seeds so `checkAiKnowledgeIndex` can evaluate
them once the extractor is enabled.

| Field | Purpose |
|---|---|
| `scopeModules` | Limits a claim to specific Gradle modules |
| `forbiddenReferences` | Rejects forbidden import/package references inside scoped modules |
| `forbiddenDependencies` | Rejects forbidden external dependencies in scoped modules |
| `allowedTargetModules` | Restricts project-module dependencies to an explicit allow-list |
| `verifiedBy` | Names existing tests that act as architecture evidence |
| `requiredTests` | Requires deterministic or boundary test classes to stay present |
| `requiredEvidenceTypes` | Requires extractor evidence such as `discovery-evidence` |
| `requiredDocs` | Requires normative documentation paths/globs to exist |
| `severity` | Marks the claim as `error`, `warning`, or `info` for gating |

## Normative Regelsuche claims

| Claim | Severity | Rule style | Notes |
|---|---|---|---|
| `no-infrastructure-in-core` | `error` | scoped forbidden refs/deps + `verifiedBy` | Core stays free of framework and infrastructure leakage |
| `search-kernel-clean` | `error` | forbidden refs/deps + allowed targets | Search kernel may depend only on core/e-graph |
| `validation-kernel-clean` | `error` | forbidden refs/deps + allowed targets | Validation kernel stays on the core side of the boundary |
| `persistence-port-clean` | `error` | forbidden refs/deps + allowed targets | Persistence port remains driver-free |
| `hibernate-isolated` | `error` | scoped forbidden refs/deps | Hibernate/JPA stay out of non-adapter library modules |
| `acyclic-module-graph` | `error` | `verifiedBy` + required docs | Module directions remain documented and test-backed |
| `deterministic-discovery` | `error` | required tests/evidence/docs | Discovery reproducibility keeps evidence and regression tests |
| `port-interfaces-first` | `warning` | `verifiedBy` + required docs | Advisory until richer API-shape checks exist |

## Capability seed structure

| Field | Purpose |
|---|---|
| `id` | Unique capability identifier |
| `label` | Human-readable name |
| `description` | Capability description |
| `modules` | Contributing Gradle modules |
| `ownerModules` | Primary owning modules |
| `packages` | Java packages belonging to the capability |
| `typePatterns` | Globs matching Java type names |
| `testPatterns` | Globs matching test classes |
| `docPatterns` | Documentation globs |
| `evidenceTypes` | Produced or linked evidence types |
| `jmhPatterns` | JMH sources for performance-sensitive capabilities |
| `tags` | Indexing and filtering tags |

## Capabilities

### rewrite-search

Term rewrite search over algebraic expressions using configurable strategies.

- **Owner module**: `regelsuche-search`
- **Evidence**: `discovery-evidence`
- **JMH benchmarks**: `app/src/jmh`

### equality-saturation

E-graph construction and equality saturation for simultaneous exploration of
equivalent expression forms.

- **Owner module**: `regelsuche-egraph`
- **Evidence**: `discovery-evidence`

### hypothesis-mining

Candidate rewrite-rule mining from successful paths via anti-unification and
pattern generalisation.

- **Owner module**: `regelsuche-learning`
- **Evidence**: `hypothesis-evidence`, `discovery-evidence`

### counterexample-search

Validation of algebraic identities and candidate rules by numerical or symbolic
counterexample search.

- **Owner module**: `regelsuche-validation`
- **Evidence**: `validation-evidence`

### proof-workflows

Proof-oriented completion, reduction and relation-search workflows.

- **Owner module**: `regelsuche-math-algorithms`
- **Evidence**: `proof-evidence`, `discovery-evidence`

### discovery

Recording and replay of discovered transformation paths with reproducibility
metadata.

- **Owner module**: `regelsuche-discovery`
- **Evidence**: `discovery-evidence`
- **JMH/benchmark sources**: `regelsuche-benchmarks/src`

### persistence

Checkpointable search-job persistence via ports and adapters.

- **Owner module**: `regelsuche-persistence`
- **Evidence**: `persistence-evidence`

### web-workbench

Browser workbench for rule editing, search, proof review and experiments.

- **Owner module**: `app`
- **Evidence**: `ui-evidence`

## Evidence type reference

| Evidence type | Produced by |
|---|---|
| `discovery-evidence` | Discovery campaigns and gallery generation |
| `hypothesis-evidence` | Hypothesis mining runs |
| `validation-evidence` | Counterexample search and rule quality checks |
| `proof-evidence` | Proof execution and proof bridge |
| `persistence-evidence` | Checkpoint/restore integration tests |
| `ui-evidence` | Browser E2E tests |
