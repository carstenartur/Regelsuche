# Release-readiness Maven parity implementation plan

> **For agentic workers:** Use superpowers:executing-plans for this approved, isolated implementation. Phase 2 requires the parent's review of the committed Phase 1 evidence.

**Goal:** Connect Maven's actual hidden-rule producer and qualified release runner to the existing Java verifier, then retire only the replaced release-verification Python decision.

**Architecture:** `app` owns its Maven hidden-rule output. The final `regelsuche-quality-aggregate` module invokes the existing release runner and verifier during `verify`, after explicit App and Release dependencies. Legacy Gradle producers and historical output remain separate.

**Tech stack:** JDK 25, Maven 3.9.x, existing Surefire/JUnit, Jackson 2.22.2 and Networknt 2.0.7. The existing verifier requires Linux AMD64 and explicit native access.

**Spec:** The approved #749 source audit/design at commit `42f5050a28d07fe81bef6fe897b7694699d8f79f`, and `docs/release-readiness-java-verification.md`.

## Global constraints

- Reuse `ReleaseReadinessRunner.runQualified(Path)`, `write(Path, ReleaseRun)` and `ReleaseReadinessEvidenceVerifier.verify(Path, Path)`.
- Preserve all existing schemas, canonical identities, qualification counts, work and 48-file retained fixture bytes.
- Maven owns only `app/target/reports/hidden-rule-pilot` and `regelsuche-quality-aggregate/target/reports/release-readiness-qualified` for this integration.
- Missing, stale, symlinked or skipped producer evidence must fail; unsupported native/platform capabilities must not skip successfully.
- No Gradle runs by this agent, GitHub writes, protected studies, new verification implementation or Maven-only completion claim.

## Phase 1: actual Maven production and parity

- [x] Add failing build-contract controls for explicit `verify` ownership, App/Release dependencies and narrowly scoped Maven freshness.
- [x] Challenge the existing `HiddenRulePilotCampaignTest` to write its actual report under Maven `target`; observe the old `build` output fail that control.
- [x] Set an explicit App Maven output property; retain the current Gradle default and remove only that module's prior Maven hidden-rule output at `initialize`.
- [x] Add `MavenQualifiedReleaseReadinessIT`, bound by a mandatory Surefire execution at `verify`, with `--enable-native-access=ALL-UNNAMED`. Admit the exact current App output and a fresh owned aggregate output before invoking the existing runner and verifier.
- [x] Add real missing/legacy-only/stale/symlink admission controls. Run the actual Maven lifecycle, including its existing package-integrity gate, with the real hidden-rule producer and existing Java verification controls selected.
- [x] Verify that an actual Maven `initialize` removes only the two owned output trees. Verify that missing current production fails even when prior successful output was supplied.
- [x] Run the unchanged Python verifier and all 26 Python negative controls on the actual Maven-produced qualified root; compare the frozen fixture and historical identities without rewriting them.
- [x] Request the parent's exclusive `:regelsuche-release:installDist` run, then execute that actual Java CLI against the same positive and mutated evidence roots.
- [x] Record exact source, commands, outcomes and artifact hashes; commit Phase 1 and stop for parent review.

## Phase 2: retirement after reviewed parity

- [x] Replace the root release-verification Python Exec decisions with the existing Java verifier and JUnit controls.
- [x] Replace all four verifier calls in the two release-reproduction scripts with the Java 25 CLI from the explicitly built `installDist`; preserve their current orchestration.
- [x] Remove only the now-unused release verifier, its Python controls and identity/binding helpers. Keep `release_readiness_files.py` for the #514 collector.
- [x] Retain the new Maven evidence paths in CI/release artifact uploads and update contracts/docs without changing required authorities, selectors or historical receipts.
- [ ] Repeat the relevant positive and negative Maven/CLI controls and submit the separate retirement commit for review.
