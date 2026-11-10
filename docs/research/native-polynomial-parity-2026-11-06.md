# Polynomial simplification procedures, 2026-11-06

The opt-in `POLYNOMIAL_SIMPLIFICATION_V1` task profile returns equivalent
representations with arithmetic-operation costs no higher than the fixed SymPy
1.14.0 portfolio on every supported input in this experiment. Both native
profiles use more complete sequence wall time and CPU in this measurement.
The learned profile does not establish an additional quality or total-cost
benefit over the native baseline.

This is a historical observation under the frozen protocol below. The current
[research methodology](../research-methodology.md) explains the purpose and
reference choice; this editorial clarification does not rerun the experiment.

## Observed quality

The frozen protocol contains 24 historical supported polynomial case IDs,
three unsupported controls and 48 additional public variants. There are **72
supported case IDs but 70 distinct input strings**: two of the predeclared
variants duplicate another input. Three rotated repetitions of three profiles
produce 675 retained rows: 648 valid supported results and 27 expected unsupported
results. All three repetitions have the same quality outcomes.

| Native profile, per repetition | Lower cost than portfolio | Same cost | Higher cost | Incomplete |
| --- | ---: | ---: | ---: | ---: |
| Native baseline | 3 | 69 | 0 | 0 |
| Native learned, acquired and restored | 3 | 69 | 0 | 0 |

On the 24 historical supported inputs, each profile, including SymPy, improves
21 expressions and retains three. One native result has a lower output cost
than SymPy; the other 23 tie. The 48 additional variants contribute two lower-cost
results and 46 ties. Unsupported controls are excluded from these denominators.
Acceptance compares every case and repetition separately; a cost reduction on
one input does not offset a cost increase on another.

The three strictly lower-cost outputs below are from repetition zero and occur in
both native profiles. The objective counts arithmetic syntax operations, not
execution latency or global mathematical simplicity.

| Input | Native output | SymPy portfolio output | Native / SymPy cost |
| --- | --- | --- | ---: |
| `((a*b+c)*(a*b-c)+c*c)*(d+7)` | `(a*b)^2*(d+7)` | `a**2*b**2*(d+7)` | 4 / 5 |
| `6*z*x+9*w*y+6*z*y+9*w*x` | `(9*w+6*z)*(x+y)` | `3*(3*w+2*z)*(x+y)` | 5 / 6 |
| `(6*x+2)*z+(6*y+9)*z` | `(6*(x+y)+11)*z` | `z*(6*x+6*y+11)` | 4 / 5 |

## Measured resources

These are medians of three complete sequences, each containing 75 case IDs
(72 supported and three controls), with no warmup or query cache. The learned
sequence includes a producer JVM, acquisition, persisted snapshot, shutdown,
and a fresh consumer JVM that restores and reproves the same bytes. SymPy pays
all fixed identity/simplify/factor/cancel/horner attempts. A query has a two-second
deadline, and native logical work is bounded separately from elapsed time.

| Profile | Sequence wall seconds | Worker CPU seconds | Controller CPU seconds | Combined CPU seconds | Worker peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Native baseline | 9.201 | 23.294 | 0.559 | 23.845 | 269.652 |
| Native learned | 11.228 | 28.818 | 0.945 | 29.746 | 277.668 |
| SymPy portfolio | 2.063 | 1.915 | 0.057 | 1.974 | 111.301 |

Combined CPU is calculated per sequence before taking its median. Worker CPU
includes all threads, process startup and shutdown. RSS is the maximum process
peak within each sequence, not a simultaneous sum or a whole-machine memory
measurement. Every one of the 12 worker receipts has exit code zero, graceful
EOF, measured CPU and RSS. Controller wall includes query binding and independent
audits. Shared runtime retention and final evidence export are outside these
per-profile boundaries. The separately retained outer command receipt reports
73.252 seconds wall and 174.315 seconds child-tree CPU through child exit;
writing that final outer receipt is outside its boundary.

## Source and evidence binding

- Protocol: [`native-polynomial-v1.json`](../../config/benchmarks/native-polynomial-v1.json),
  SHA-256 `c35902e09c4c6e0b49e7b1c5257bf5f696ca867ae8c6a1880a071f29ad758f3e`.
- Measured local source commit: `972beee2e1af329e3ca91f2067714a6bb2b30cf9`.
  Published content-identical source commit:
  [`5eb2aea3147854e9859adc4cbd3c2f7427579985`](https://github.com/carstenartur/Regelsuche/commit/5eb2aea3147854e9859adc4cbd3c2f7427579985).
  Both have Git tree `dc0aa3960f8e8c836b754cae72d4f8c5c8cd31e8`.
  Commit metadata differs because publication used Git data operations.
- Retained runtime bytes hash:
  `sha256:bd78e256db76b855b8fc07c24f47793cfcf6cc512ecaf569c21708adf3a65e9f`.
  The runner copied the Java classpath and launched the worker from those bytes.
- Raw archive `Regelsuche-Native-Polynomvergleich-2026-11-06.zip`:
  15,417,091 bytes, SHA-256
  `0bb6d2bdaf71899507cd66d56c8093515ce8c85fa204d112cc888af30b3bb106`.
  It contains the manifest-bound evidence inventory and outer command receipt.
- [Machine-readable observation summary](native-polynomial-parity-2026-11-06.json).

The original rows, process receipts, persisted models and runtime bytes were
rechecked unchanged after independent review strengthened offline verification.
The verifier now binds case IDs to frozen input strings, checks lifecycle and
resource matrices and pinned versions, and retains failed startup ownership.
Regressions reproduce relabeling, missing receipts, corrupt CPU partitions,
negative durations, wrong SymPy versions and both startup failure stages.
Unavailable resource measurements explicitly prevent performance acceptance.

For a new run, use the commands in the
[native API and reproduction guide](../native-polynomial-simplification.md).
The existing typed-comparison CI job executes the native comparison and retains
its complete evidence; no extra workflow was added. New runtime/JDK bytes can
change timing and runtime hashes, so a reproduction is a new observation rather
than the same measurement.

## Validation and limits

Java 25 with the project's exact Maven plugin versions passed the affected
learning reactor: **3,598 tests, zero failures, errors or skips**. The workflow
semantics contract passed 5/5 with the existing two workflows, and the complete
external polynomial Python suite passed 88/88 after review fixes. Hosted checks
qualify the final published head separately.

The additional variants were committed before their first evaluation, but they
are public variants of known families, not a sealed independent-family holdout.
The measured quality criterion is arithmetic-operation count on these finite
rational-polynomial inputs under the declared budgets. It does not establish
globally minimal representations. Variable denominators and nonpolynomial
functions remain outside the shared comparison domain. Resource observations
apply to the complete sequences and process boundaries above. Historical
protocols and default task profiles remain unchanged. Native normalization and
factoring improve the common capability set; these data do not attribute that
improvement to learning.

