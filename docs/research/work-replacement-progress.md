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

## P02 — integrated

[PR #1050](https://github.com/carstenartur/Regelsuche/pull/1050) extends the
incremental provider contract through a registered wrapper without adding a
search-to-learning dependency. Native v1 behavior and mathematical authorization
remain separate from registration. Opening, matching, suspension, closing and
atomic overruns are paid; staged execution does not pre-open later providers.

- Qualified head: `3d08afe62314375b6b7aac9e9a086c30de104f68`; local reviewed
  counterpart: `cfb63e6b66252115e7d5be859dda206a22aaa527`.
- Full hosted CI [35529617769](https://github.com/carstenartur/Regelsuche/actions/runs/35529617769)
  passed all six authorities, `Checkout-local ciCheck` and CodeQL. Independent
  scoped review approved the final corrections without remaining findings.
- Local final evidence: 845 core, 495 search and 951 learning tests, with no
  failures, errors or skips. Genuine pre-fix regressions cover dropped incomplete
  accounting, unconsumed wrong-source candidates, lost typed move metadata and
  unversioned status reports. Historical artifacts remain unchanged.
- Merge/main: `f751f90f58ee10aa122c42218b66a13760053aa0`; tree
  `ff3271d79f108f125f2e0294e7c656c662034339` equals the qualified head's tree.
  Post-merge CI [35532193563](https://github.com/carstenartur/Regelsuche/actions/runs/35532193563)
  also passed all six authorities and `Checkout-local ciCheck`; this was
  reconfirmed on 2026-09-27.

## P03 — integrated

[PR #1051](https://github.com/carstenartur/Regelsuche/pull/1051) adds the actual
learned/restored checked-schema cursor, paid preparation and independently
suspendable prepaid application phases. Its mechanism is documented in
[the lazy-cursor report](lazy-checked-schema-cursor.md).

Accepted main is `ee6dd6892d899662c857c1f2bc2861e233e68563`, tree
`e1bee2a7712ff95f14b2c3513f565ff6c26bde8b`. Post-merge CI
[36360112301, attempt 2](https://github.com/carstenartur/Regelsuche/actions/runs/36360112301/attempts/2)
passed all six authorities and `ciCheck` job `108741752739`; CodeQL also passed.
The first post-merge SymPy attempt failed during external apt setup before tests;
the unchanged-source rerun completed. P03 is the accepted predecessor for P04.

## P04 — additive foundation, total qualification incomplete

[PR #1054](https://github.com/carstenartur/Regelsuche/pull/1054) prepares native
immutable Expr execution through the existing shared frontier, pickers and
managed cursor lifecycle. Primitive rules, the existing compiled-program
interpreter, learned/restored checked schemata, history ranking and independent
admission/final replay operate through additive native interfaces. Historical
facades and their accounting contracts remain supported. No new learner, search
algorithm, proof-result cache or mathematical authority is introduced.

This increment does **not** complete P04. Native search and native export publish
`PARTIAL_ATOMIC_INVENTORY`: total accounting is incomplete, `withinBudget` is
false, and the public search outcome is `INCONCLUSIVE`. Real mathematical
witnesses and selected incumbents remain available; observed outcomes/work/peaks
are diagnostic. Explicit exports can retain diagnostic artefacts with
`INCONCLUSIVE` and `completeBoundedRelation=false`; the convenience export throws
with its attempted receipt. Production-phase native registration remains refused.
See [the native qualification boundary](native-expr-qualification.md).

[PR #1055](https://github.com/carstenartur/Regelsuche/pull/1055) continues that
foundation with temporary ownership observation, paid failed attempts, explicit
fresh-process learned-proof replay and preserved export chronology. Its review
corrections publish completed list, snapshot and buffer allocations before their
debit can fail, and preserve the primary failure when frame cleanup repeats it.
Normal charges, mathematical validation and all partial-qualification flags stay
unchanged. The ten reproduced review regressions pass with these corrections;
the affected Java 25 suites pass 1,058 core, 577 search and 1,058 learning tests,
with no failures, errors or skipped tests. This is local verification, not a
replacement for the final hosted checks or a completed P04 acceptance.

PR #1055 was integrated at `c9709bf4ec421ed42bee4bf157f841a3c6b33096`.
Its source-bound public P03 compatibility evidence is retained on
[`codex/pr1055-integration-evidence-20260929`](https://github.com/carstenartur/Regelsuche/tree/ba34bfe1e1ee561dd4cfcef9ab32da07682a7c04/docs/research/evidence/work-replacement/pr1055-p03-compatibility).

[PR #1065](https://github.com/carstenartur/Regelsuche/pull/1065) adds the paid
structural store index and was integrated at
`51d699d092c8a6dae139c2c3b539d774d96955e3` on 2026-10-06. Its actual final head
`014343918090e2ab6cebc21d8b80f0579af384df` passed all six authorities and
`Checkout-local ciCheck` in [CI 37443413603](https://github.com/carstenartur/Regelsuche/actions/runs/37443413603).
All three regular review threads are resolved, including failure-atomic ownership,
preservation of the primary rollback failure and partial index-commit phases.
The earlier [structural-index evidence](https://github.com/carstenartur/Regelsuche/tree/c8f0223b0dde93fe4ff4b53fd18522f5e413acd7/docs/research/evidence/work-replacement/pr1065-p04-structural-index)
is bound to `596afa3`, not falsely relabeled as the later final head.

The next bounded slice starts at `8a98a14e6713225801832918befd7c12d5cb74ba`:
direct AST/history validation owns and pays its visit arena and completed scalar
renderings, including failed debits and cleanup. Input observation failures retain
their normal resource result. This is native revision V6, still partial; its
new review/CI qualification is separate from #1065's completed integration.

Every continuation's exact final head must pass the full affected module suites,
unchanged public P03 legacy corpus comparison, independent review and all hosted
authorities. Earlier green slice CI is intermediate evidence only.
Remaining P04 work is a follow-up: complete the atomic ownership/work inventory
(canonicalizer, normalization, matching/backtracking, domain/instantiation,
formatting and application-side export helpers), qualify the full native public
differential under its declared revision, and rerun complete accounting/retention
acceptance. P04 has no completion checkmark. P05–P12 remain subsequent work, and
no economic learning advantage is asserted.
