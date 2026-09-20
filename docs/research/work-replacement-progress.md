# Work replacement implementation evidence

## M0 — integrated

Baseline: `7aec9ae0a1619dda98f859d1277ac8b287471423`. Its tree equals reviewed/tested `ec1e5d40dde7b52f086621de704ac25b5a2b1c2a`.

- #1047 head `ab55d6aff6cfd252733f2b519932e969c171008d`, CI 35513137346: all required authorities successful; merge `79f785c572dbc9e0afdb398b92e5fccd716b2872`.
- #1048 head `ec1e5d40dde7b52f086621de704ac25b5a2b1c2a`, CI 35514858944: all required authorities successful; merge is baseline above. Hosted artifact ID 10606910982, reported SHA256 `c28f72b376d96d73860a6b882edf9e5d288fc4c1f17fe857119e14c898f7bee7`.
- Fresh local Java 25.0.2: `./gradlew --no-daemon :regelsuche-search:test :regelsuche-learning:test` succeeded. JUnit XML: 471 search + 914 learning tests, zero failures/errors/skips.
- Independent code review found no blocking issue. The inherited extreme-score JSON aggregate overflow is assigned to P01; long work overflow must continue to fail explicitly.
- Post-merge main CI [35517882778](https://github.com/carstenartur/Regelsuche/actions/runs/35517882778) passed every required authority and `ciCheck` at that baseline. Main artifact 10607464502 has API-reported SHA256 `24a5bbc5882338c48cd2a4c5692281bd8f7715e38fc01c42487aa5c9daa90e06`; its ZIP was not downloaded.
- Existing negative learning results and all quality thresholds remain unchanged.

## P01 — integrated

[PR #1049](https://github.com/carstenartur/Regelsuche/pull/1049) adds the v3
lifecycle account and comparison contract. Local implementation commits are
`2ea17e71a69824272cf132a45c76c374a628f322` and
`5784d1c7e0f2fa5be701049cb5d40ecdf645b5f2`.

- Eight phases, exact-once delegated work, four arms, three real session profiles,
  retained failures/unrun rows and fair total-budget allocation are implemented.
- Java 25 tests: prior core 844 and search 471 runs passed; after the regular
  GitHub review corrections below, the full learning suite passes 938 tests with
  zero failures, errors or skips. The integration uses eight separate child JVMs
  and both stream profiles. Primitive baselines do not restore learned models.
- Independent review found two Important defects: omitted proof-output bytes and
  an oracle fixture that duplicated L1. Corrections charge all declared adapter
  output and require a supplied learned schema in the chosen oracle witness;
  focused RED/GREEN and the learning suite pass. Scoped independent re-review
  approved both corrections without further findings; full hosted CI passed.
- Regular GitHub review identified three additional boundaries: evaluation could
  carry a TRAIN context, unknown information-regime strings could bypass family
  isolation, and child queries used a hardcoded deadline and reported ERROR.
  Pre-fix behavioral RED reproduced all three (four failing tests). Corrections
  require FROZEN_EVALUATION before callbacks, accept only PUBLIC_DEVELOPMENT and
  SEALED_FAMILY_HOLDOUT, and enforce the declared child query deadline with typed
  TIMEOUT. Killed-child unknown work marks accounting incomplete; paid receipts
  and remaining rows survive, preventing economic claims. The startup/restore
  deadline remains separate. All 20 focused tests pass, including a real hanging
  JVM with a 50ms query deadline and the existing three-profile integration.
  Scoped independent review approved these corrections at local `5e6963ad`
  without findings; the corresponding published head passed full hosted CI.
- Actual pre-fix regressions also cover extreme numeric export, failed final
  replay and missing output evidence. For the new accounting/orchestration APIs,
  tests were written first but behavioral RED was not executed before filling the
  implementation. Four post-implementation fault mutations supplement that
  disclosed process deviation; they are not described as original RED evidence.
- Initial hosted CI failed the accumulated context-growth limit against an older
  baseline. The existing accepted-predecessor procedure independently qualified
  M0 and retained both accepted and rejected reports; see
  [the provenance](../../ai-knowledge/baseline-history/2026-09-20-main7aec9ae0-provenance.json).
  No threshold, selector or exception changed. P01 passed its own full current-head CI.

Qualified head: `75848fd26639c1aa675b87a9dccdfa7fd9f16e83`, full CI
[35523215069](https://github.com/carstenartur/Regelsuche/actions/runs/35523215069).
All six authorities, `Checkout-local ciCheck` and CodeQL passed. All three GitHub
review threads were resolved after their fixes and independent scoped review.
Merge/main: `3deeb519357cdd6e67184d8104c0d925a0cf19d7`; the reread tree
`1f54ebfc29986396cc1db530829234f708a617c6` exactly matches the qualified head.
Commit bindings and API-reported artifact digests are retained in
[the integration receipt](evidence/work-replacement/p01-integration.json).

No economic learning advantage is asserted by P01.

## P02 — in progress

Starts from qualified integrated main `3deeb519357cdd6e67184d8104c0d925a0cf19d7`.
Native v1 reference output was captured before any P02 edits for early success,
complete enumeration, atomic generation overrun and state limits. The new
registered contract must preserve those historical bytes and the existing
mathematical checker boundary. P03–P12 remain pending.
