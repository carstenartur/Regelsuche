# Plugin Distribution Client Implementation Plan

**Goal:** Install and retain one authenticated dependency closure through bounded network retrieval and atomic activation under an external checkpoint authority.

**Architecture:** Existing index, trust-state and artifact verifiers remain authoritative. A Java client stages immutable content-addressed generations, then an externally trusted compare-and-set authority atomically selects a generation and trust checkpoint. Local files are a cache, never a rollback-protected authority.

**Tech Stack:** Java 25, JDK HTTP client, Ed25519, Jackson, NIO, JUnit 5, Maven/Gradle.

**Spec:** The design sent to and approved by the coordinating agent for issue #104 on 2026-09-13; public contract in `docs/plugin-distribution-client.md`.

## Global constraints

- Preserve the existing HTTPS/local-file index schema; network installation accepts HTTPS sources only.
- Do not execute package code or claim a hosted catalog, independently published examples, or verified build reproducibility.
- Require explicit pinned root, trust domain, origins, finite resource budgets and an externally trusted authority.
- No default local-file or in-memory production checkpoint authority; test fixtures may model the authority contract.
- Work only in this worktree. No GitHub writes, Maven install, spawned agents or unleased Gradle process.

## Implementation sequence

- [x] Inspect issue #104, existing verifiers, resolver, URI constraints and Maven baseline.
- [x] Add `PluginDistributionTransport` and real loopback tests: a bounded subscriber, strict origins, no redirects, deadline cancellation and per-operation resource budget.
- [x] Add versioned `PluginArtifactProvenance`: strict parsing, Ed25519 publisher assertion binding artifact hash, entry identity, provenance URI and source/build digests; no fetched keys.
- [x] Add `PluginInstallationEvidence`, `PluginCheckpointAuthority` and immutable generation persistence with canonical file hashes, non-symlink paths and atomic staging promotion.
- [x] Add `PluginDistributionClient`: authenticated trust/index download, deterministic closure resolution, artifact snapshot and provenance verification, then authority CAS activation.
- [x] Add update, whole-closure removal, and retained-generation rollback reverified under current trust; preserve old authority state on download, verification, staging or CAS rejection.
- [x] Test real HTTPS/signature/ filesystem mutations, failed authority calls, replay/fork/gap and rejected rollback after revocation.
- [x] Document the authority's durability and atomicity obligations, failure recovery, retained evidence, usage, supported scope and remaining external work.
- [x] Run targeted new and existing trust/index regressions and commit the bounded implementation.

Each production behavior is introduced after its failing test. The coordinating agent arranges independent review and current-main full CI.
