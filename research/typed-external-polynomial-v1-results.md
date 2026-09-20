# Typed external polynomial pilot: first qualified Java-25 run

## Evidence and scope

Measured commit: `8d6fa40e7777d437ff72b70473d2edfb741c3ba0`.
[Hosted Java-25 run 35487837107](https://github.com/carstenartur/Regelsuche/actions/runs/35487837107)
completed successfully. Artifact `10598167782`,
`typed-external-polynomial-8d6fa40e7777d437ff72b70473d2edfb741c3ba0`, has ZIP SHA-256
`060a9c6f8ce643ad58f929b38a66107bb2d5fab6d71d09249bf770ee737abd8b`.
Its protocol hash is `ccf2d953cbc43b99f9eb9ec5abe3512bcd9eb8df63a3ae2ccbf8c09264d7078e`.

This is the [frozen public development protocol](typed-external-polynomial-v1.md):
24 polynomial sources, three unsupported controls, nine configurations and three
repetitions, or 729 rows. All 648 supported rows are valid, with no technical
errors, invalid identities, timeouts or internal work overruns. There are 81
unsupported-control rows. These are not 729 independent mathematical tasks.
This is separate from the earlier local 16-case/2-budget experiment and from the
original untyped polynomial pilot; none of their results is rewritten here.

The specialized workflow passed 29 targeted Java tests and 29 Python tests.
That is not the complete product/quality CI and does not qualify a newer head.
The first failed digest-contract run and all its rows remain in artifact
`10597661862`; they are not silently converted into successful Java comparisons.

## Quality: 24 distinct polynomial tasks, not repeated rows

| Learned TRAIN-selected versus | Better | Equal | Worse |
|---|---:|---:|---:|
| Primitive inventory-order control | 0 | 24 | 0 |
| SymPy simplify | 3 | 13 | 8 |
| SymPy factor | 2 | 13 | 9 |
| SymPy cancel | 9 | 8 | 7 |
| Paid SymPy portfolio | 1 | 13 | 10 |

All five Java configurations produce equal-quality outputs on every task.
They improve 17/24 inputs; the portfolio improves 21/24. The one portfolio win
is `((a*b+c)*(a*b-c)+c*c)*(d+7)`: Java retains `(a*b)^2*(d+7)` (four
surface operations), whereas the portfolio chooses `a^2*b^2*(d+7)` (five).
The primitive control finds the same form, so this is not a learning win.
Counting a power as one surface operation does not establish fewer CPU instructions.

## Logical work, including unsuccessful attempts and actual training

Query totals below include all three repetitions of the 24 supported tasks.
Unsupported controls never enter a worker. Training is performed once per profile.

| Profile | Query work | TRAIN work | Sum |
|---|---:|---:|---:|
| BASE | 26,850 | 0 | 26,850 |
| EXPERT (fixed default weights) | 40,356 | 0 | 40,356 |
| PRIMITIVE_SELECTED | 26,850 | 4,262 | 31,112 |
| LEARNED_NAIVE | 37,599 | 2,734 | 40,333 |
| LEARNED_RANKED | 37,599 | 3,812 | 41,411 |

Both TRAIN-selected policies choose inventory order. The learned profiles add
10,749 query units (about 40%) before charging any training. There is therefore
no demonstrated amortization on this workload. These logical units are not
complete CPU instruction counts; whole-worker user/system CPU and controller
CPU are retained separately in the artifact.

Per Java profile, the selected paths expand to 132 primitive steps over all
repetitions. Learning reduces the visible selected edges from 132 to 75 and
uses 30 learned edges, but it does not reduce the primitive expansion or the
total search work. Shorter displayed paths must not be reported as faster search.

## Independent selected-path audit

The additional `audit_typed` command first runs the existing artifact verification,
then checks every selected Java path and every primitive macro intermediate using
the independent exact rational-polynomial judge. It binds source/output syntax,
selected edges to admitted search events and exact predecessor states, depth
increments, primitive rule lists, unconditional premises and replay work. It also
checks the primitive/search/verification work partition. No artifact is rewritten.

```sh
PYTHONPATH=scripts python -m external_polynomial_comparison.audit_typed \
  --output build/reports/typed-external-polynomial-comparison
```

The audit is **offline**, outside timed solver requests. Its cost is not secretly
charged as zero query work, and it establishes neither execution authentication
nor a Lean proof. Registered-rule regeneration remains the Java verifier's job.

The downloaded artifact's 360 Java candidates and 660 primitive selected steps
passed the local independent mathematical/lineage audit, including the learned
intermediates. Fourteen new regressions use compact selected fields from genuine
Java-25 receipts, with their origin retained in the gzip fixture. Thirteen fail
against the initial no-op audit; all fourteen pass after implementation. Mutations
include wrong intermediates with correct final answers, graph-disconnected paths,
forged replay costs, premise injection and duplicate JSON fields.

The new audit plus 21 existing pure-Python comparison tests pass locally. The full
local discovery also attempts `test_classpath`, which fails at Gradle distribution
DNS resolution in this network-restricted runtime. The hosted run above passed
that integration test; the new audit's hosted integration still needs its own run.
Self-review was performed by the author; no independent code-review approval is
implied by the mathematical audit or tests.

## Next implementation question

The existing policy selector maximizes TRAIN target hits before minimizing work;
its targets are endpoints found by the target-free learner. The external use case
instead has no target and minimizes representation cost. A separate source-only
selection experiment should score verified improvements under a shared complete
budget and learn when the extra learned provider is worthwhile. Its strong control
must remain the primitive inventory-order path, not the slower fixed-weight profile.
Any new curriculum, policy choice or corpus requires a separate protocol revision.
The current negative learning result remains unchanged.
