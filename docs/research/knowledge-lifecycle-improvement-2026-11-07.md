# Prepared knowledge and paid self-improvement, 2026-11-07

The reusable APIs are implemented and the complete lifecycle experiment ran.
**This observation does not establish a total-cost advantage over the existing
persistent learned session or targeted SymPy factorization.** Repeated model
reproofs are avoided, but lower preparation work does not by itself prove a
complete runtime improvement. All measured outcomes, including this negative
result, are retained.

## Implementation

- `CumulativeTaskKnowledge.emptyPrepared/restorePrepared/loadPrepared` opt into
  the existing indexed matcher and carry that choice through later generations.
  The default APIs, mathematical inventory, snapshot format, proof checker and
  primitive fallback remain compatible.
- `TaskKnowledgeSession` owns bounded LRU retention of verified immutable
  generations. Every file request still uses the strict reader and content-address
  checks. Reuse requires identical complete canonical bytes and the same inventory.
  A checked prepared successor may enter via `retainVerified`, without a second
  reproof solely to enter the cache. No answer or process-global cache exists.
- `TaskKnowledgeSelfImprover` executes the existing cumulative batch learner,
  then measures old/candidate generations on alpha-disjoint promotion sources.
  Adoption requires no individual solution/score regression and projected
  repayment of paid formation/evaluation costs in both wall time and whole-JVM CPU.
  Its explicit horizon is a prediction. Rejected attempts are still paid; a
  rejection cannot recover training work that already happened.

The [API guide](../prepared-task-knowledge.md) gives examples and accounting
boundaries. These are opt-in library APIs, not a migration of every consumer or
an automatically invented general mathematical procedure. The existing learner
still forms the actual compositions; no second learning/search engine was added.

## Frozen experiment and information

The [protocol](../../config/benchmarks/knowledge-lifecycle-improvement-v1.json)
was fixed and its executable source published before the first lifecycle run.
SHA256: `e36bf231ea8c8005078df94ea7c99d2326b6f3299fadd910eccb346322ccc4c2`.

Eight source-only node-goal queries contain nested cancellation compositions at
three depths, compound/offset substitutions, an already-small control and a
negative goal. They are **one public development family**, with explicit
teaching/validation overlap; they are not a sealed independent-family holdout.
Workers receive source and maximum node count, never an output or reference path.

| Profile | Actual work |
| --- | --- |
| PRIMITIVE | Empty native knowledge, no unused training, one fresh JVM |
| LEGACY_SESSION | Acquire two seed generations, save/exit; fresh JVM strictly loads once and retains the existing immutable object |
| LEGACY_RELOAD | Same acquisition; fresh consumer strictly loads/reproves before every query block |
| PREPARED_REUSE | Same acquisition; fresh owned session independently proves its first load, then reads/checks and reuses identical generations |
| MEASURED_IMPROVEMENT | Same seed acquisition; prepared consumer actually attempts a third composition generation and a no-op batch, paying all trials and retaining whichever generation its cost gate selects |
| SYMPY | Pinned 1.14.0; genuinely receives the source-only teaching prelude, declines learning, chooses minimum-node output from identity and targeted `factor` |

Lengths are 1/16 blocks of the eight queries (8/128 requests), three rotated
repetitions: **36 complete lifecycles, 2,448 query rows and 60 owned processes**.
No extra warmup or answer cache; ordinary library caches and JIT stay enabled.
All learned native arms pay the same two seed blocks and producer exit plus a
fresh consumer. Including LEGACY_SESSION prevents an artificial claim based
only on repeated loads that an existing persistent caller could already avoid.

The independent auditor uses sparse exact Fractions and a bounded arithmetic
AST, without Java or SymPy. It checks universal polynomial equality, output node
count, declared goal/budget, source/task/generation binding, and continuous native
selected paths with individually checked identities. It verifies the paid
adoption ledger from actual old/candidate samples. All 2,448 audits pass: 2,142
solutions and 306 retained misses. Every profile reaches 7/8 or 112/128 goals;
the negative node goal stays unsolved, without being promoted to an impossibility
certificate.

## Complete observed costs

Cells are medians of three actual complete sequences. Wall includes acquisition,
fresh first restore/reproof, all reads/lookups, learning and unsuccessful attempts,
queries, transport, independent auditing and shutdown. Full CPU sums all owned
worker processes/threads and the controller. RSS is each sequence's maximum
worker-process peak, not summed peaks or complete-machine memory.

| Requests | Profile | Wall seconds | Full CPU seconds | Worker peak RSS MiB |
| ---: | --- | ---: | ---: | ---: |
| 8 | PRIMITIVE | 0.683 | 1.709 | 130.199 |
| 8 | LEGACY_SESSION | 1.630 | 3.964 | 126.688 |
| 8 | LEGACY_RELOAD | 1.738 | 4.102 | 126.254 |
| 8 | PREPARED_REUSE | 1.761 | 3.961 | 128.004 |
| 8 | MEASURED_IMPROVEMENT | 1.977 | 5.368 | 177.371 |
| 8 | SYMPY | 0.383 | 0.383 | 72.973 |
| 128 | PRIMITIVE | 1.651 | 5.632 | 206.051 |
| 128 | LEGACY_SESSION | 2.834 | 7.887 | 216.453 |
| 128 | LEGACY_RELOAD | 2.977 | 9.189 | 219.008 |
| 128 | PREPARED_REUSE | 2.856 | 8.448 | 204.449 |
| 128 | MEASURED_IMPROVEMENT | 2.925 | 8.671 | 209.684 |
| 128 | SYMPY | 0.786 | 0.754 | 78.969 |

Prepared reuse avoids 45 additional full reproof/preparation calls across its six
sequences after the six fresh first loads. The existing persistent control also
avoids those reloads. Its retained output-node vector is identical to all native
arms: **3, 5, 7, 5, 7, 7, 13, 1**. SymPy returns **3, 3, 3, 5, 7, 5, 5, 1**.
Because some native results have higher node counts, the strict verifier issues
no native/SymPy aggregate performance ratio, despite equal goal-attainment vectors.

The 128-request prepared/legacy-persistent paired wall ratios are 0.886, 1.051
and 1.082. Prepared/repeated-load ratios are 0.915, 1.132 and 0.876. Thus even
the lower prepared group median versus repeated loads is not a win in every
repetition. These shared-host observations do not establish a robust broad gain.

## The improvement forecast versus actual execution

The existing logical-work learner proposes the third-generation composition in
all six improvement sequences. The new cost gate rejects its predicted payoff
in all three eight-query sequences and adopts it in all three 128-query sequences.
Every following no-op batch is reported as NO_CANDIDATE. All twelve attempts and
their actual costs remain in the ledger; no decision grants a refund.

**Adoption is not proof of actual amortization.** The complete mixed stream still
takes more median wall/CPU than the persistent control. Promotion samples contain
one deep offset case, while the actual stream mixes shallow, compound, deep,
negative and already-solved cases. A correct future query count does not make
that sample distribution representative. This observation identifies a limitation
of the forecast, rather than justifying a performance claim from its own decision.
It does not isolate the contribution of matching, JIT, setup or sample mix.

No inputs, lengths, procedures, budgets or performance thresholds were retuned
after observing these results. No longer-stream break-even is extrapolated.
SymPy remains the faster and, on several cases, more compact procedure here.
This is separate from the historical Brahmagupta query result and the polynomial
operation-count comparison; their inputs/goals and conclusions are unchanged.

## Provenance and reproduction

Measured local source `ce772150396b63bde34e5693e2baddb6cc94b4fe`; published
content-identical source
[`c8c1f3a85bd2082316c8cfc99ef6375e803a7e25`](https://github.com/carstenartur/Regelsuche/commit/c8c1f3a85bd2082316c8cfc99ef6375e803a7e25),
tree `7063b49354fc91149327d0c6fc59b8b2191f165c`, based on main
`c3644ec29a0ae061c3f7980638f0680b21799c8d`. API publication changes commit
metadata; each full Git tree was checked for equality before execution.
Java runtime hash `sha256:19af326966210500060ab838591767de50ba5f1f53e88d58cf6cb6cafb7ce472`;
Python runtime hash `sha256:a17667b1f9d177a2baa29ff20684f4a224c65d084e29505cdc59ea356f4bdb58`.
The exact ordered Java classpath and Python packages/checked-hash bytecode were
retained and launched. Interpreter/JDK identities are separate from a whole OS.

Observed environment: shared Linux x86-64, nine affinity-visible CPUs, eight-CPU
cgroup quota and 8 GiB limit, Java25.0.5+8 and Python3.12.14. Java uses `-Xmx512m`;
this is not a shared 512MiB process-address-space limit. Runtime retention and
Python bytecode preparation occur once outside per-profile lifecycles, inside
the outer command: **83.256 seconds wall / 193.604 seconds child-tree CPU**.
Software build/install, supervisor imports, final outer receipt writing, archive
packaging and file delivery are outside that boundary.

[Numeric observations](knowledge-lifecycle-improvement-2026-11-07.json) retain
groups, paired outcomes, the six real improvement ledgers and representative
per-case outputs. The complete separately supplied archive retains all original
rows, checkpoints, runtimes, models, manifests, receipts and qualification logs.
Archive `Regelsuche-Wissenswiederverwendung-2026-11-07.zip`: **34,714,760 bytes**;
SHA256 `093c09b2cefefb3d6361a245d96f6e198799da8fa093a7743074eb1d9f362716`.
Original evidence independently reverified unchanged with `complete=true`.

Qualification: exact-source fresh Java25 Maven learning reactor **3,547 tests**,
zero failures/errors/skips; complete current-branch Python suite **98 tests**.
The unchanged pinned AI Knowledge Extractor0.1.10 coverage/complexity gates pass
(17 existing hotspots). Source-only runtime qualification used a fresh checkout
because previous experiment files were rehydrated into the older working tree;
those files were preserved and are not part of this PR or measured runtime.

Reproduce: `bash gradle/run-knowledge-lifecycle-improvement.sh`. Offline verification:

```sh
PYTHONPATH=scripts python3 -B -m external_polynomial_comparison.run_knowledge_lifecycle \
  --verify --output /absolute/extraction/evidence
```

## Implementation decisions and remaining scope

- Proceed inline under Carsten's explicit approval, using opt-in APIs and existing
  mechanisms. Existing callers require adoption; defaults do not silently become
  globally faster. No additional search/learning kernel was introduced.
- Keep this PR based on main, independent of open PR1150, rather than overwriting
  or stacking that comparison. The cost is a separate focused change to review.
- Use controlled clocks only for deterministic state-transition tests. Actual
  evidence uses the production process meter and independent wait4 receipts;
  process samples can still be affected by JVM/background activity.
- Include primitive and already-persistent controls and targeted SymPy factorization
  before measurement. This makes broad superiority harder to claim and exposes
  the actual remaining overhead.
- Use a fresh feature checkout when host synchronization prevented file isolation
  in the older linked worktree. Extra local checkout space was paid; prior files
  and the separate comparison were preserved.

The implemented step is prepared reuse and measured adoption of existing
compositions. Autonomous invention of new mathematical procedures, Euclid/Ferrari
comparison, broad independent-family transfer and complete P01–P12 delivery are
not established by this change. The failed complete-cost prediction here remains
an explicit target for further work.
