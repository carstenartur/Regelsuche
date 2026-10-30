# Cumulative task knowledge Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task by task.

**Goal:** Persist and extend checked task knowledge over multiple generations.
**Architecture:** Extend the existing formation pipeline with replayed task pairs;
coordinate immutable generations, typed routing and validation in the learning
module; continue using the existing task solver and independent replay.
**Tech Stack:** Java 25, existing Jackson and JUnit, existing Gradle/Maven checks.
**Spec:** docs/superpowers/specs/2026-10-30-cumulative-task-knowledge-design.md

## Global constraints
No historical experiment, workflow, quality threshold or proof gate changes.
Local Java 21 compatibility copies are not published or called Java 25 evidence.

## Review focus
Restored malicious models; same-generation feedback; training/validation overlap;
selection costs exceeding budgets; rejected generations losing paid work.

## Tasks
- [x] Extend CheckedLearnedSchemaModel formation through a shared helper; expose
  package-private extraction from completed task solutions. Preserve old output.
- [x] Add CumulativeTaskKnowledge with typed strategies, immutable parent/child
  state, paid profile selection, batch validation and retained acquisition ledger.
- [x] Add bounded content-addressed persistence and independently checked restore.
- [x] Exercise real generations 0/1/2/3 with compound unseen substitutions,
  primitive-only and frozen-predecessor controls, negative cases and fresh-JVM load.
- [ ] Document actual positive/negative results, full lifecycle costs, environment
  limits and the exact hosted-CI status. Publish one PR against current main.

## Verification
./gradlew --no-daemon :regelsuche-learning:test --tests '*CumulativeTaskKnowledgeTest'
./gradlew --no-daemon :regelsuche-core:test :regelsuche-search:test :regelsuche-learning:test
./gradlew --no-daemon --no-configuration-cache ciCheck
