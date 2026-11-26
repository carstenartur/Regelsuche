# Conflict-aware matching implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Prove reusable bounds for matchings with incompatible edge assignments.

**Architecture:** A bounded LP producer emits weighted-cover certificates.
An independent graph verifier derives the bound without rebuilding or solving
the LP. The SDK only translates budgets.

**Tech Stack:** Java 25, existing ExactRational, CertificateWork and exact LP search.

**Spec:** `docs/superpowers/specs/2026-11-27-conflict-matching.md`

## Global constraints

- General mathematics stays in `regelsuche-math-algorithms`.
- Preserve exact source binding and one shared work/time/cell allowance.
- No claim of integral optimality or a uniform Greedy deadline.
- Strict warnings apply to production and tests.

## Review focus

- Canonical edge numbering versus input order and duplicate edges.
- Empty graphs, isolated vertices and dimensions that exceed cell limits.
- Fractional bounds and integer rounding without floating point.
- Adversarial certificate source/weight changes.
- Nested solver work charged on failures and final cancellation before publication.

### Task 1: Core bound and independent replay

- [x] Add `ConflictMatchingTest` covering conflict pair, alternative matching,
  triangle, four-clique, source tampering and bounded execution; observe failure.
- [x] Implement `ConflictMatching` request/certificate/result and LP producer,
  plus `ConflictMatchingVerifier` direct weighted-edge replay.
- [x] Run focused tests, then exhaustive small-graph reference comparisons.

### Task 2: SDK and documentation

- [x] Add SDK consumer tests, observe failure, then implement thin
  `ConflictMatchingSdk.search/verify` forwarding.
- [x] Document proof, resource model and limits; update capabilities and strategy
  index with current code anchors and actual consumer findings.
- [ ] Run complete affected SDK reactor and actual strict compilation.

### Task 3: Independent review and integration

- [x] Review the entire source/test/doc diff independently and address findings.
- [ ] Publish a focused ordinary PR with exact verification evidence.
- [ ] Integrate only after the required current-head CI and review gates pass.
