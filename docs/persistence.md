# Persistence and Full Mode

Regelsuche separates short-lived search state, compact metadata, text search and mathematical graph provenance.

```text
RAM / EGraph / TranspositionTable
→ active search run only

Compact SearchTraceStore / graph artifacts
→ large raw traces for replay/reporting (id-interned + compact paths)

PostgreSQL + Hibernate ORM
→ experiments, search runs, hypotheses, counterexamples, benchmarks, seeds, reports, proof-job metadata

Hibernate Search + Lucene backend
→ full-text and facet search over rules, hypotheses, reports, seeds and benchmarks

Neo4j (optional)
→ mathematical knowledge graph / provenance, never a replacement for PostgreSQL metadata
```

## Modes

| Mode | Use when | External services |
| --- | --- | --- |
| `IN_MEMORY` | tests, short CLI runs, no persistence | none |
| `JSON_FILE` | demo mode and single Docker image | none |
| `POSTGRESQL` | full metadata persistence must be available | PostgreSQL required; startup fails if config is incomplete |
| `POSTGRESQL_WITH_JSON_FALLBACK` | normal Full Mode: PostgreSQL metadata plus JSON graph/search artifacts | PostgreSQL when configured; otherwise JSON fallback path must be writable |
| `REMOTE_NEO4J` | optional graph-provenance backend | Neo4j required |

## Configuration

PostgreSQL/Hibernate mode is configured through environment variables or matching JVM properties:

```bash
REGELSUCHE_PERSISTENCE_MODE=POSTGRESQL_WITH_JSON_FALLBACK
REGELSUCHE_PERSISTENCE_PATH=/opt/regelsuche/data
POSTGRES_URL=jdbc:postgresql://postgres:5432/regelsuche
POSTGRES_USER=regelsuche
POSTGRES_PASSWORD=replace-with-a-secret
```

Optional Neo4j graph provenance uses the existing variables:

```bash
NEO4J_URI=bolt://neo4j:7687
NEO4J_USER=neo4j
NEO4J_PASSWORD=replace-with-a-secret
```

The values `regelsuche-demo` in `docker-compose.yml` are deliberate local-demo
fallbacks, not production credentials. Set `POSTGRES_PASSWORD` and, when the
Neo4j profile is enabled, `NEO4J_PASSWORD` before any non-local deployment.
Keep database ports unexposed whenever direct host access is unnecessary.

When `Neo4jSearchGraphRepository` stores a `SearchGraphRecord`, it now writes
both the round-trippable JSON snapshot and a typed provenance graph. The
provenance model contains `Hypothesis`, `Counterexample`, `ProofAttempt`,
`SearchRun`, `MacroMove`, `SeedExpression`, `AssumptionSignature`,
`BenchmarkRun` and `TransformationPath` entities plus typed relationships such
as `SUPPORTED_BY`, `REFUTED_BY`, `GENERALIZES`, `DERIVED_FROM`, `USEFUL_FOR`,
`REPLAY_OF` and `GENERATED_BY`. In-memory provenance queries are available via
`ProvenanceGraphQueries` for strongest hypotheses, complex-domain refutations,
most reused macro rules and derivation lineage.

## Docker Compose

Start the standard Full Mode:

```bash
docker compose up --build
```

This starts the application and PostgreSQL with healthchecks and persistent
volumes. Published application and database ports bind to `127.0.0.1` by
default. The application itself still uses unauthenticated HTTP unless a
`WebSecurityConfig` with authentication and TLS is supplied; therefore the
Compose defaults are a local workstation profile, not an internet-facing
production deployment.

Neo4j is optional:

```bash
docker compose --profile neo4j up --build
```

Its browser and Bolt ports also bind to `127.0.0.1`. In production, prefer not
to publish PostgreSQL or Neo4j ports at all and let only the application reach
them on the container network.

The proof-worker image is a placeholder profile for deployments that want a separate prover container:

```bash
docker compose --profile proof-worker up --build
```

## Migrations

Versioned SQL migrations live in `regelsuche-persistence-hibernate/src/main/resources/db/migration` and are applied by `DatabaseMigrationRunner` before Hibernate ORM starts. The runner records applied versions in `regelsuche_schema_history` and skips already-applied migrations, so repeated startup is safe.

The migrations create relational tables and JSONB columns for compact metadata. Hibernate Search with the Lucene backend is the primary full-text/facet mechanism; the PostgreSQL `search_vector` migration remains a conservative fallback for direct SQL diagnostics.

## Backup strategy

Back up PostgreSQL with `pg_dump` for metadata:

```bash
pg_dump "$POSTGRES_URL" --username "$POSTGRES_USER" --format custom --file regelsuche-metadata.dump
```

Back up JSON artifacts by copying `REGELSUCHE_PERSISTENCE_PATH`; those files contain large graph/search artifacts and demo exports. Back up Neo4j separately only when the optional Neo4j profile is used.

## Tests

Normal CI continues to run lightweight unit tests via:

```bash
./gradlew test
```

Heavy PostgreSQL/Hibernate integration coverage is isolated in Docker E2E tests:

```bash
./gradlew :app:dockerE2eTest --tests de.regelsuche.dockere2e.HibernateFullModePersistenceTest
```

## Compact SearchTraceStore strategy (Discovery Epic Teil 5)

Search traces for large discovery runs are persisted in a dedicated compact store:

- **Hash-consing:** expressions, rule ids and assumption signatures are interned once.
- **Edges:** store only numeric references (`fromExprId`, `toExprId`, `ruleId`, `assumptionsId`).
- **Paths:** top-k hot paths may stay as raw edge-id arrays; remaining paths use
  **delta + varint encoding**.
- **Replay:** compressed paths are decoded back to edge-id sequences and then expanded via intern tables.

This removes JSON-only duplication as the limiting factor for large discovery spaces
and keeps replay/report generation reconstructable.
