# Work replacement implementation evidence

## M0 — integrated

Baseline: `7aec9ae0a1619dda98f859d1277ac8b287471423`. Its tree equals reviewed/tested `ec1e5d40dde7b52f086621de704ac25b5a2b1c2a`.

- #1047 head `ab55d6aff6cfd252733f2b519932e969c171008d`, CI 35513137346: all required authorities successful; merge `79f785c572dbc9e0afdb398b92e5fccd716b2872`.
- #1048 head `ec1e5d40dde7b52f086621de704ac25b5a2b1c2a`, CI 35514858944: all required authorities successful; merge is baseline above. Hosted artifact ID 10606910982, reported SHA256 `c28f72b376d96d73860a6b882edf9e5d288fc4c1f17fe857119e14c898f7bee7`.
- Fresh local Java 25.0.2: `./gradlew --no-daemon :regelsuche-search:test :regelsuche-learning:test` succeeded. JUnit XML: 471 search + 914 learning tests, zero failures/errors/skips.
- Independent code review found no blocking issue. The inherited extreme-score JSON aggregate overflow is assigned to P01; long work overflow must continue to fail explicitly.
- Post-merge main CI [35517882778](https://github.com/carstenartur/Regelsuche/actions/runs/35517882778) passed every required authority and `ciCheck` at that baseline. Main artifact 10607464502 has API-reported SHA256 `24a5bbc5882338c48cd2a4c5692281bd8f7715e38fc01c42487aa5c9daa90e06`; its ZIP was not downloaded.
- Existing negative learning results and all quality thresholds remain unchanged.

## P01 — implemented, qualification in progress

[Draft PR #1049](https://github.com/carstenartur/Regelsuche/pull/1049) adds the v3
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
  approved both corrections without further findings; full hosted CI remains pending.
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
  Scoped independent review and hosted qualification of these corrections remain
  pending; earlier approvals do not cover the new code.
- Actual pre-fix regressions also cover extreme numeric export, failed final
  replay and missing output evidence. For the new accounting/orchestration APIs,
  tests were written first but behavioral RED was not executed before filling the
  implementation. Four post-implementation fault mutations supplement that
  disclosed process deviation; they are not described as original RED evidence.
- Initial hosted CI failed the accumulated context-growth limit against an older
  baseline. The existing accepted-predecessor procedure independently qualified
  M0 and retained both accepted and rejected reports; see
  [the provenance](../../ai-knowledge/baseline-history/2026-09-20-main7aec9ae0-provenance.json).
  No threshold, selector or exception changed. P01 still needs its full current-head CI.

No economic learning advantage is asserted by P01. P02–P12 are pending.
