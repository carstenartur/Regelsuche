# Maven evidence authority implementation plan

> For agentic workers: execute this plan inline using superpowers:executing-plans.

**Goal** — Make the Maven lifecycle own the original evidence and decisions in issue #749.
**Architecture** — Reuse producer sources, Java decisions and historical baselines. Bind portable evidence replay to verify and expensive runtime production to full verification. Remove an adapter only after its obligations execute successfully.
**Tech stack** — Java 25, Maven 3.9.9+, Failsafe, JUnit, JMH 1.36, Testcontainers.
**Spec** — [Acceptance-to-code map](../specs/2026-11-20-maven-evidence-authority-design.md)

## Global constraints

Current six-warmup policy, historical two-warmup policy, all family ceilings and canonical report schemas stay byte-bound. Preserve failed samples, infrastructure failure and independent source-archive execution.

## Review focus

Missing benchmarks, altered policy bytes, INCONCLUSIVE, stale raw output and worker-JVM contamination each require verification. Hardware-dependent thresholds always decide on the actual run.

## Tasks

- [ ] Add mandatory Failsafe raw replay in regelsuche-quality with CI archive provenance, checked-in exact raw inputs and original reports; compare all decisions, all 29 rows and failure behavior.
- [ ] Compile app/src/jmh via the real pinned annotation processor in a dedicated full Maven module. Execute fresh forks for latency and allocation and apply production publication, v3 and allocation validators. Run the new Maven producer.
- [ ] Move the diagnostic SymPy JMH matrix and independent runtime tests behind the pinned external-tool boundary before retiring the old isolated workflow.
- [ ] Migrate remaining build, quality collection, browser, solver and release obligations identified in the spec. Exercise negative controls, then retire the corresponding adapter.
- [ ] Exercise clean checkout, unfiltered full source archive, packaged distribution and release qualification. Update README, testing and release docs to reflect observed authority.
