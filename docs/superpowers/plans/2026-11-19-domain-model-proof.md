# Domain model proof implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development or
> superpowers:executing-plans. Track the checks below against actual output.

**Goal:** Connect retained discovery candidates to the existing checked affine
recurrence backend, with reproducible receipts and a usable browser workflow.

**Architecture:** A discovery-layer adapter obtains selected objects only from
an exact workspace replay. It checks finite data separately, then invokes the
existing Java model proof engine. Persistence, API and UI consume that same
source-bound receipt rather than constructing mathematical authority themselves.

**Tech stack:** Java 25, existing Gradle/Maven modules, Jackson, JUnit 5 and the
existing pinned Playwright runtime; no new dependencies outside existing modules.

**Spec:** `docs/superpowers/specs/2026-11-19-domain-model-proof-design.md`

## Global constraints

- Preserve original export bytes, workspace identity and source claim boundaries.
- Finite observations do not establish their unique infinite continuation.
- Supported affine model dimension is one through three; unsupported inputs are
  reported, not truncated or silently approximated.
- Keep phase work separate and bounded; unavailable internal work stays explicit.
- Do not touch the independent #749 Maven migration or publish a release.

## Review focus

1. Non-palindromic recurrence coefficients must preserve their source order.
2. Constant, affine and cubic difference models must use correct initial states.
3. Foreign or rehashed false receipts must fail source/proof verification.
4. Missing candidates, exhausted budgets and oversized models must stay distinct.
5. Switching/reloading exports must not show another run's proof or download.

## Task 1: Bound mathematical consumer

- [x] Add meaningful failing tests for both domains, asymmetric coefficients,
  unsupported dimensions, finite negatives, budgets and rehashed proof damage.
- [x] Implement `DomainModelProof.prove(workspace[, budget])`, private `Receipt`,
  canonical model binding and retained receipt verification in discovery.
- [x] Add the existing math-algorithms module dependency in Gradle and Maven.
- [x] Run focused discovery tests; document the exact proof scope and work.

## Task 2: Retention and HTTP contract

- [x] Test retention/reload, source mismatch, corruption, idempotence, limits and
  unchanged original files, then implement hash-addressed proof storage.
- [x] Add bound model-proof POST and receipt GET to the existing API dispatcher
  and canonical OpenAPI contract; extend method/media/body-limit controls.
- [x] Run real HTTP and offline schema checks for actual generated receipts.

## Task 3: Browser workflow and integration

- [x] Add the proof action, model/assumption/result/work display and receipt link.
- [x] Exercise real backend/browser flows for both domains, unsupported input,
  stale selection, reload, keyboard and 390-pixel layout.
- [x] Update the domain workflow documentation and strategy index only where the
  implementation changes their claims; retain explicit remaining #1020 scope.
- [x] Review independently, fix material findings and run affected checks.
- [ ] Publish one focused PR, await applicable green CI and integrate the tested
  tree. The PR and its CI records track this remote acceptance gate.

## Local verification, 2026-11-19

The full discovery suite passed 270 tests before the final storage review
fixes. The final review run passed all 40 affected tests: five repository,
eight real HTTP, five schema, eighteen model-browser and four existing
export-browser tests. The corrected OpenAPI regression run passed twelve
tests. All these runs had zero failures, errors or skips.

Review fixes have observed failing controls for altered POST/GET receipt
bytes, damaged retained files, two separate Java writers at the storage limit,
and an impossible zero-order proof. Their final controls pass. The UI verifies
the retained response before displaying a result, and the 390-pixel screenshots
were visually inspected.

The pinned extractor `v0.1.10` passed `aiKnowledgeCheck` using the existing Java
25 build and an explicitly supplied Java 17 extractor toolchain. No baseline,
limit or warning policy was changed. Hosted CI remains the acceptance gate for
the full repository and both build systems.
