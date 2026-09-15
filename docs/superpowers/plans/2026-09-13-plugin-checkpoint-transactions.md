# Recoverable plugin checkpoint transactions implementation plan

> Execute inline with superpowers:executing-plans; no subagents.

**Goal:** Make the existing authenticated Java-plugin lifecycle callable with an external PostgreSQL checkpoint provider and explicit recoverable outcomes.

**Architecture:** Preserve all existing v1 hashes and the old strong boolean CAS interface. Factor preparation from the existing client; an additive coordinator submits a complete operation to PostgreSQL, whose decision receipt and selected state are committed together. Recovery only consults the external operation ledger. No local pending file authorizes a commit.

**Tech Stack:** Java 25, JDBC/PostgreSQL (existing driver), existing Jackson/Ed25519/HTTPS client, JUnit and existing pinned PostgreSQL Testcontainers boundary.

**Spec:** Parent-approved source audit from 2026-09-13, retained at target/issue104-hosted-audit/source-audit-and-design.md; public contract will be docs/plugin-checkpoint-transactions.md.

## Constraints

- No Gradle, API writes, protected studies, production databases/secrets, default activation or catalog-latest authority.
- Existing PluginCheckpointAuthority and original canonical v1 formats remain unchanged.
- OUTCOME_UNKNOWN never means unchanged, denied, committed or permission to reset.
- Existing JDBC graph/JSON fallback is not checkpoint authority.
- The deployment's external DB/backup/administrator boundary remains an operator assumption.
- No Docker/PG executable or socket is currently available; compile and retain real integration tests, report execution limitation honestly.

## Sequence

- [x] Add operation/scope/outcome model with fixed finite IDs, exact expected/update/checkpoint/root/intent binding and canonical roundtrip controls.
- [x] Factor existing client's native verified preparation from its final legacy CAS; add coordinator install/remove/rollback/recover. Control committed-but-lost answer, stale failure, different intent reuse, restart, missing local generation and local pending forgery avoidance with real HTTPS materialization.
- [x] Implement provisioned PostgreSQL schema with restricted per-slot role, atomic row lock + operation receipt + selected pair update, immutable receipt/idempotence and finite capacity/deadlines. Add real provider tests using the existing pinned container, including JDBC commit-ack interruption at the real boundary.
- [x] Wire explicit package CLI with strict bounded config/operation inputs, no hidden credentials/default sources, distinct confirmed/rejected/unknown exit statuses.
- [x] Run focused offline Maven with fresh compilation; attempt actual Docker integration to record infrastructure failure, never count skip as green.
- [x] Compare original trust/index/installation canonical outputs against exact base, retain receipt/logs and document the reviewable implementation.

## Verification receipt

- Fresh app compilation: 60 passed controls across 13 suites; one existing Gradle-configured standalone evidence comparison was skipped. No failures or errors. The new client/CLI controls have no skips.
- Behavioral REDs retained: lost-answer recovery returned UNKNOWN instead of the known committed decision; the old CLI rejected the new package route with its unrelated exit code. A separate initial missing-API compilation failure is not counted as a behavioral RED.
- Separately compiled original client from `22eac9b68705be13dca3a2378c161d8a988b2329`, then current client, consumed the same fixed HTTPS inputs. Install/remove/rollback produced 34 identical generation files, 25223 bytes total.
- Six current PostgreSQL controls compile after a fresh test-output rebuild. The real Testcontainers invocation failed during initialization because Docker is unavailable; none of their SQL bodies ran locally. Actual pinned-container execution remains required.
- Detailed commands, hashes, compiler freshness and limitations are retained in `target/issue104-hosted-audit/verification-receipt.json`; logs and the per-file v1 comparison accompany it.
- Independent review, hosted CI and operator deployment qualification remain pending. No production database, secret, runtime activation or protected study was used.
