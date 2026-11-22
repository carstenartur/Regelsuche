# Scoped Logic Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retain and inspect a verified `scoped-logic@v1` export in the existing workspace.

**Architecture:** Extend the existing descriptor dispatch and immutable source
view. Scoped replay remains unavailable without a configured independent proof
receiver; the existing persistence and HTTP routes preserve original bytes.

**Tech Stack:** Existing Java 25/Maven, Jackson, JUnit and browser workbench.

**Spec:** `docs/superpowers/specs/2026-11-21-scoped-logic-workspace-design.md`

## Global Constraints

- Retain the existing 512 KiB export limit, duplicate/unknown-field checks and exact handoff binding.
- No source search, obligation parsing or proof worker execution during import.
- Existing three domains keep their canonical views and replay behavior.
- Source-only scoped import grants no new proof, external novelty, promotion or release authority.
- Keep current gates and historical receipts unchanged; #1020 remains open.

## Review Focus

- Small valid logical sources must not be described as outside the replay budget.
- A retained certificate must not be presented as a freshly checked proof.
- Direct API requests must fail closed even when their UI controls are hidden.
- Original CRLF/whitespace bytes and manifest identity must survive reload/download.
- Foreign revisions or forged descriptors/handoffs must not gain admission.

## Task 1: Immutable scoped source view

**Files:** `DomainExportWorkspace.java`, `DomainSnapshotAssessment.java` and
`ScopedLogicWorkspaceTest.java` in the discovery domain package; existing
`DomainDownstreamValidation.java`, `DomainModelProof.java`, their closed schemas,
and `regelsuche-release/.../DomainModelProofSchemaTest.java`.

**Interfaces:** Existing `fromVerified`, `replaySupported`, `replay` and
`assess` remain the entry points. Add only a package-local precise replay-refusal
reason; obtain the scoped descriptor from the existing producer's metadata.

- [x] Add failing import, original-byte, replay-refusal and blocked-assessment controls, including an untrusted source claiming CONFIRMED with a certificate.
- [x] Run the focused Maven tests and record the unsupported-domain failure (three controls).
- [x] Admit the exact scoped descriptor without running its receiver; preserve old view rendering.
- [x] Report `SCOPED_PROOF_REPLAY_NOT_CONFIGURED` for the missing scoped receiver and `REPLAY_NOT_CONFIGURED` in assessment.
- [x] Run new and existing workspace, assessment, validation and model-proof controls (58 tests, 22-module Maven reactor, zero failures/errors/skips).
- [x] Independently review the exported contracts; reproduce and close all three positive-rebind and six borrowed-payload/execution schema gaps. All 38 discovery and 11 schema controls pass with the corrected closed schemas.

## Task 2: Existing HTTP and browser surface

**Files:** Existing `DomainExportWorkspaceHttpTest.java`,
`DomainExportWorkspaceBrowserTest.java`, `discovery-domains.js`,
`discovery-domains.html`, and `docs/domain-export-workspaces.md`.

**Interfaces:** Existing four-file upload, list/load, original file download,
source-bound replay/assessment/validation/model-proof endpoints remain unchanged.

- [x] Add the actual scoped upload/load/download and direct refused-action controls.
- [x] Add the browser source import/reload/download flow with disabled replay and hidden unrelated proof controls.
- [x] Render logical source labels and the missing-receiver explanation; document the precise scope.
- [x] Run focused HTTP tests, including POST/GET byte equality for all three source-only receipts (11 controls green). The combined rerun stopped with exit137 after 49 successful discovery/schema tests; the separate bounded HTTP reactor succeeded. Browser test compilation passed; pinned hosted browser execution remains required for visual/product acceptance.
- [x] Check affected strategy code anchors and obtain independent review. No Critical/Important findings remain; the existing DomainModelProof class anchor remains at line20.
- [ ] Publish a draft with exact verification limits, retarget it only onto accepted main, and require the unchanged hosted product/browser gates before integration.
