# ADR 0002: Local verification owns tests; Testcontainers owns infrastructure

- Status: Accepted
- Date: 2026-07-18
- Decision owners: Regelsuche maintainers
- Tracking issue: #399

## Context

Regelsuche accumulated many narrowly scoped GitHub Actions workflows while
implementing release evidence, discovery qualification, generated artifacts and
container checks. Several workflows contain executable assertions directly in
YAML: fixed-port `docker run` commands, REST smoke tests, inline Python schema
checks, byte-parity comparisons and negative cases.

That structure has two undesirable effects:

1. GitHub Actions becomes part of the test implementation instead of an
   interchangeable CI runner.
2. A contributor with only a Git checkout cannot execute exactly the same
   verification surface through documented project commands.

The repository already demonstrates the preferred model in
`app/src/dockerE2eTest`: JUnit 5 owns the assertions and Testcontainers owns
PostgreSQL and Docker-image lifecycles.

## Decision

### Tests and verifiers are repository code

All functional assertions must live in one of the following checked-in forms:

- JUnit tests;
- Gradle tasks that invoke checked-in application or verifier code;
- checked-in standalone verifier programs called by Gradle.

Anonymous Bash or Python embedded only in workflow YAML must not be the sole
implementation of a test or evidence invariant.

### Infrastructure integration uses Testcontainers

Docker-backed integration tests use Testcontainers for:

- image construction;
- random host-port allocation;
- readiness checks;
- command execution inside containers;
- log capture;
- cleanup;
- Docker availability handling.

PostgreSQL, the standard application image, the proof image and later
release/evidence parity images follow this rule. Fixed host ports and manually
named long-lived test containers are prohibited in integration workflows.

### Gradle exposes stable local entry points

The root build exposes:

- `integrationTest` for non-browser infrastructure integration;
- `browserE2eTest` for Playwright flows;
- `verificationTest` for all JVM tests plus infrastructure integration.

The same tasks are the CI entry points. A plain checkout plus the documented
JDK, Docker and browser prerequisites is sufficient.

### GitHub Actions remains an orchestrator

GitHub Actions may:

- check out source;
- select a JDK;
- install browser host dependencies;
- cache dependencies;
- call Gradle tasks;
- upload reports and evidence;
- publish releases or Pages content using credentials.

It must not own domain assertions or infrastructure lifecycle logic.
Publication workflows that intrinsically require GitHub credentials remain
separate from test execution.

## Consequences

### Positive

- Local and CI behavior share the same implementation.
- Container tests use random ports and deterministic cleanup.
- Failures have ordinary JUnit reports and Testcontainers logs.
- Alternative CI systems can run the same commands.
- Workflow count and duplicated setup can be reduced safely.

### Costs

- Existing workflow-owned assertions must be migrated incrementally.
- Some evidence verifiers need dedicated Java or standalone verifier entry
  points before their workflows can be collapsed.
- Docker-backed tests remain slower and require a Docker-compatible runtime.

## Migration

The first slice:

1. adds root-level local verification tasks;
2. moves `Dockerfile.proof` integration coverage into Testcontainers;
3. removes the redundant standalone Docker-build workflow;
4. documents the local/CI boundary.

Subsequent slices move release-readiness, autonomous-walkthrough and independent
reproduction container parity into locally runnable suites, then consolidate
the evidence-specific workflows.
