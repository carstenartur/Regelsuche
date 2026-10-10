# PR #1081: explicit witness-work delegation

GitHub review thread `PRRT_kwDOSiJVMM6q7wHc` identified a legacy boundary missed
by the earlier ownership review. An unrelated enclosing `RetainedOperation`
caused `MoveSearchObjective.finish` to omit the historical witness-copy charge,
although the legacy environment does not import the outer observer's account.

Two behavioral regressions reproduce this on published head
`b3553e09c867b336e1f09e480ff51dc08c8809ae`: search work falls from 11 to 9, and a
budget exhausted by witness materialization incorrectly reports
`QUALITY_REACHED`. The first attempt only failed to compile its new sink fixture;
that attempt is retained separately and is not the behavioral RED.

Fix `d372e99b3dae354b73d5f7a23baa2f833d2ad732` makes the existing search environment
explicitly declare whether its own additional-work account pays the observed
witness producer. The legacy default is false; native execution opts in only
with its actual accounting session. An ambient observer no longer changes that
choice. Native copy work remains charged exactly once.

- Focused regression and native controls: **14 tests, 2 failures before the fix;
  14 tests, no failures/errors/skips after it**. Both continuation contracts are
  exercised, including exact legacy result equality and the tight-budget proof.
- Fresh full core/search/learning suites at `d372e99`: **1,147 + 673 + 1,089 =
  2,909 tests**, no failures/errors/skips; isolated classes/reports and unchanged
  source verified.
- Full public differential rerun at `d372e99` in two new JVMs: all 13 output files
  byte-identical between runs **and to the earlier `1a09fdb` matrix**. Thus the
  native semantics, diagnostic work, frozen-budget negatives and 24/26 ample
  strict comparisons remain unchanged.

`raw-evidence.tar.gz` retains the failing attempts, successful logs/XML, source
bindings, full corpus and the unchanged AI-quality gate. `manifest.json` binds
each file by SHA-256. `source-history.bundle` preserves the exact local fix commit
above, requiring the already published `b3553e0` predecessor. Required CI on the
final published head remains a separate integration gate.

No threshold, budget, mathematical checker, historical revision or native
PARTIAL flag changes. This fixes the PR's accounting defect; it does not complete
P04's bounded execution contract or any of P05–P12.
