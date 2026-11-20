# Expression export workspace implementation plan

> **For agentic workers:** Use `superpowers:executing-plans` for the existing #1020 productization task.

**Goal:** Import, retain and reproduce the existing `expression-rewrite@v1` export through the same workspace used for the two built-in sequence domains.

**Architecture:** Preserve the original four files and their manifest identity. Reconstruct the existing expression domain from its recorded target only for bounded replay; loading must not execute search. Replay compares the complete canonical evidence. Sequence-only downstream checks remain unavailable for expression exports.

**Tech stack:** Existing Java 25 domain runner, Jackson, HTTP server and JavaScript Workbench; JUnit and Playwright.

**Spec:** Issue #1020 and `docs/domain-export-workspaces.md`.

## Constraints and review focus

- Preserve previously retained sequence workspace hashes and original source bytes.
- Bind the expected target, seed, complete trace, budget and certificate through the existing evidence contract.
- Bound expression parsing before the constructor is invoked; oversized sources remain readable without replay.
- Reject missing or incompatible expression payload configuration; changed but rehashed execution traces must fail replay.
- Keep formal proof, novelty, promotion and public-evidence states unchanged.

## Tasks

- [x] Reproduce the currently rejected genuine expression export with a failing JUnit test; include distinct target identities and tampered-target replay.
- [x] Extend `DomainExportWorkspace` using the existing expression domain and bounded source/target admission.
- [x] Verify persistence and HTTP import, download and replay; sequence checks must return unsupported for expressions.
- [x] Show expression-specific headings and hide sequence-only actions. Exercise real browser import, replay, reload and narrow display.
- [x] Update the domain workspace/API documentation and verify focused plus related tests before review.

## Verification and independent review

The full discovery Maven reactor passed 3,059 tests with zero failures or errors
and one optional observation test skipped. The HTTP workspace/model-proof slice
passed 18 tests. All five real-browser workspace flows passed, including formula
import, replay, download, persistence, and the 390-pixel viewport.

Independent review reproduced byte-for-byte compatibility of the two existing
sequence workspace formats against the original implementation. A rehashed
export with a 20,001-character nested target remained readable but was refused
for replay before parsing. No important review findings remained.
