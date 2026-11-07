# Historical tasks versus SymPy, 2026-11-06

**Both native profiles and SymPy solve the four canonical tasks: Cassini, Pell,
Brahmagupta–Fibonacci and Sophie Germain.** On ten public variants, the native
baseline solves six, while learned native knowledge and SymPy solve all ten.
The learned native arm matches SymPy's complete success vector in this finite
experiment, but its complete lifecycle remains slower through 72 requests.

This is a separate experiment from the operation-count polynomial comparison.
It measures the requested historical goals, not whether an expression is shorter.
No production mathematical algorithm was changed to obtain these observations.

## Tasks and equal starting information

The [frozen protocol](../../config/benchmarks/historical-sympy-v1.json) supplies
only a source and general goal, or a rational affine transition, initial state
and degree bound. Query workers receive no historical name, target expression
or reference path. Equivalent valid representations are accepted.

| Task | Required result | Native base | Native learned | SymPy |
| --- | --- | --- | --- | --- |
| Cassini | Nonconstant quadratic invariant with fixed/alternating evolution; exact universal step and initial value | solved | solved | solved |
| Pell | Same invariant contract for the Pell transition | solved | solved | solved |
| Brahmagupta–Fibonacci | Exact sum of two squares with nonconstant polynomial bases | solved | solved | solved |
| Sophie Germain | Exact explicit product with two nonconstant polynomial factors | solved | solved | solved |

For example, native Cassini synthesis forms `x0^2+x0*x1-x1^2` with multiplier
`-1` and base value `-1`. SymPy forms its negative, with base value `1`; both
pass the same independent induction certificate. Neither coefficient vector is
provided to formation. Given the explicit Fibonacci transition, the native form
means `F_n*F_(n+2)-F_(n+1)^2=(-1)^(n+1)`.

The native adapter calls existing `CumulativeTaskKnowledge` APIs with
`STRUCTURAL_V1` and the existing recurrence synthesizer. SymPy 1.14.0 uses exact
monomial coefficient comparison and `Matrix.nullspace` for invariants,
`factor_list` over the rationals for factorization, and Gaussian-rational
factorization plus conjugate pairing/real-imaginary extraction for two squares.
These are supplied general procedures, not autonomous invention of a strategy.
The two-square procedure requires a rational square scalar after factorization;
it is not a complete sum-of-squares decision algorithm.

The independent auditor uses sparse exact `Fraction` arithmetic, without Java
or SymPy. It checks polynomial equality and the actual output shape, and checks
simultaneous substitution, degree, nonconstancy, variables and the base case for
each invariant. Native selected paths additionally have continuous source-bound
endpoints and individually checked polynomial identities. The induction scheme
is domain-specific symbolic reasoning, not a Lean certificate or inference of
an infinite recurrence from finitely many observed numbers.

Relevant primary SymPy documentation: [nullspace](https://docs.sympy.org/latest/tutorial/matrices.html#nullspace),
[factorization](https://docs.sympy.org/latest/modules/polys/reference.html#sympy.polys.polytools.factor_list)
and [Gaussian domains](https://docs.sympy.org/latest/modules/polys/domainsref.html#qq-i).

## Variant and control outcomes

All three repetitions give the same success vector. For the 18-task sequence:

| Cohort | Cases | Native base solved | Native learned solved | SymPy solved |
| --- | ---: | ---: | ---: | ---: |
| Canonical historical tasks | 4 | 4 | 4 | 4 |
| Public variants | 10 | 6 | 10 | 10 |
| Already-factored control | 1 | 1 | 1 | 1 |
| Negative/bounded controls | 3 | 0 | 0 | 0 |
| Total | 18 | 11 | 15 | 15 |

The four bounded baseline misses are `sophie-scaled`, `brahmagupta-shifted`,
`sophie-shifted` and `sophie-compound`. All four use actual learned applications
and satisfy the independently checked goal in the learned arm. Negative/bounded
controls remain in every profile: absence of a reported result is not promoted
to a general impossibility proof.

These are familiar public development tasks. Canonical cases overlap teaching;
`brahmagupta-shifted` and `sophie-compound` are alpha-renamings of validation
templates. The recurrence family is validated at parameters 3 and 5 and queried
at other parameters as well as the original Cassini/Pell transitions. This is
knowledge reuse on these families, not a sealed holdout, 18 independent families,
or external mathematical novelty.

## Observed query latency

The following medians use the 12 occurrences of each canonical task across the
three 72-request sequences (four repeats of the fixed task list). They include
request transport, serialization and independent audits. **They exclude learning,
loading, process startup and shutdown**, which are included in the next table.
Fixed ordering and natural library/JIT warmup influence these observations;
they are not isolated intrinsic algorithm timings.

| Canonical task | Native base, ms | Native learned, ms | SymPy, ms |
| --- | ---: | ---: | ---: |
| Cassini | 6.116 | 4.704 | 4.722 |
| Pell | 6.946 | 6.226 | 5.055 |
| Brahmagupta–Fibonacci | 58.508 | 12.029 | 44.470 |
| Sophie Germain | 52.506 | 12.339 | 4.866 |

Learning reduces observed native query latency for these four tasks. The learned
Brahmagupta query is faster than this SymPy procedure, Cassini is close, and
SymPy is faster on Pell and Sophie Germain. These observations do not pay back
the complete learned lifecycle in the measured stream.

## Complete lifecycle costs

The experiment has 27 fresh lifecycles: three profiles, lengths 1/18/72, three
rotated repetitions, no added answer cache or extra query warmup. Normal library
expression caches and JIT remain enabled. All 819 query records pass their
independent audit; all 36 owned-process receipts have graceful exit code zero
with CPU and peak RSS. There are 624 reported solutions and 195 bounded misses.

The learned arm actually executes two expression-learning blocks, two recurrence
blocks and family formation, saves, exits, then loads/reproves in a fresh JVM.
It retains two expression schemata, two exact recurrence laws and one family.
All profiles genuinely receive the same source-only teaching prelude. The
baseline and stateless SymPy procedure decline learning and incur no unused
training process. Native learning reports 174,644 API units, and restoration
5,148 units, in addition to query costs. These units omit adapter/I/O/CPU costs
and are not comparable to SymPy instructions; elapsed/CPU measurements include
the actual work.

Each cell is the median of three complete sequences. Worker CPU sums all owned
processes/threads including startup/shutdown; full CPU adds the controller.
Peak RSS is each sequence's maximum worker-process peak, not summed peaks or
whole-machine memory.

| Requests | Profile | Solved per run | Wall seconds | Full CPU seconds | Worker peak RSS MiB |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | Native base | 1 | 0.604 | 1.260 | 97.641 |
| 1 | Native learned | 1 | 2.211 | 5.507 | 199.496 |
| 1 | SymPy | 1 | 0.354 | 0.345 | 73.781 |
| 18 | Native base | 11 | 1.574 | 4.988 | 212.020 |
| 18 | Native learned | 15 | 2.688 | 7.135 | 202.379 |
| 18 | SymPy | 15 | 0.729 | 0.726 | 73.781 |
| 72 | Native base | 44 | 2.520 | 7.946 | 230.766 |
| 72 | Native learned | 60 | 3.372 | 9.062 | 198.277 |
| 72 | SymPy | 60 | 1.570 | 1.531 | 73.781 |

No baseline/SymPy performance ratio is issued for lengths 18 or 72 because their
quality vectors differ. Learned/SymPy quality matches at every index in all
repetitions; the native learned lifecycle takes more wall time in every pair.
The 72-request paired wall ratios are 2.075, 2.214 and 2.265. A longer workload
would need a separate predeclared execution; no break-even is extrapolated.

Observed host: shared Linux x86-64, nine affinity-visible CPUs, eight-CPU cgroup
quota, 8 GiB memory limit, Temurin 25.0.5+8 and Python 3.12.14. This is not an
exclusive-host precision study or universal ranking of CAS programs. Java uses
`-Xmx512m`; this is not a common 512 MiB address-space limit for both runtimes.

The outer successful command records 55.506 seconds wall and 124.702 seconds
child-tree CPU, including shared runtime retention, Python bytecode preparation
and report/manifest export. Supervisor imports, the final outer receipt write,
archive packaging and file delivery are outside that boundary. Shared preparation
is not assigned arbitrarily to a profile. No runtime/JIT/compiler overhead is
removed from the per-profile process lifecycles.

## Provenance, unsuccessful attempt and reproduction

Measured local source: `a5e53c0d99278d7830f539ee2dfa4b0a76ddf607`.
Published content-identical source:
[`f9b25697d84b96249b0c19d16f659206f6653d8d`](https://github.com/carstenartur/Regelsuche/commit/f9b25697d84b96249b0c19d16f659206f6653d8d),
Git tree `2bb6adee574c4050e8435729ba51b2fca4196ae3`, based on main
`33f482850e22fe8e3200c1f0d05a2d5e584450d0`. Commit metadata differs because
publication used Git data operations; tree equality was checked before execution.

Protocol SHA-256:
`fcd686dabd14c6bdf5318602c37b0915134a010a0ae5acc85dbe28fc48abbb1a`.
Java runtime bytes:
`sha256:e6d3488c992cc3c3de154cf894257d64f44f32c40b2a613afe87220a63aba9b1`.
Python runtime bytes:
`sha256:f2406799ca98bf2f768d6f87caf968863099f4e07cb74b6b7e7889ffde607cf1`.
Actual Java and Python package/bytecode snapshots were used for execution;
JDK/interpreter identities are recorded separately, not a complete OS image.

The archive also preserves the first 27-lifecycle/819-row attempt at local source
`8eb690a069` / published `ffc60a948f`. A Java/Python JSON-line hash mismatch
blocked native setup before its queries. All blocked rows, acquired/saved model
bytes, successful SymPy observations, process costs and the failed command
receipt are retained. It verifies as faithfully recorded incomplete evidence;
it supplies no native quality or speed ratio. The adapter-only fix unifies the
canonical trailing LF, and the executable runtime was rebuilt cleanly after
stale incremental bytecode was detected. No mathematical procedure, task or
budget changed after the first attempt. Both original attempts were independently
reverified without rerunning competitors.

The [numeric observation](historical-sympy-comparison-2026-11-06.json) retains
all case outcomes, median resources, paired comparisons and representative
outputs. The separately supplied complete original ZIP includes both attempts,
all runtime bytes, models, full rows/selected paths, manifests and receipts.

Archive `Regelsuche-Historischer-SymPy-Vergleich-2026-11-06.zip`: 63,721,895 bytes; SHA-256
`3f99331d3be2f29f72fa1e4e16fc3c01e34b7e6ebf87ddf0c078cc028f682824`.

Validation: the final exact Java-25 Maven learning reactor passes 3,592 tests
with zero failures/errors/skips; the complete Python comparison suite passes
96 tests. The focused wire-contract suite passes 6/6 after a clean build.

A fresh final review independently confirmed both original attempts, the numeric
summary and all twelve canonical latency medians. It found three observer defects:
failed READY startup could lose its process receipt, saved/restored snapshot paths
were not mandatory and fully bound, and an exact over-budget diagnostic output
was rejected instead of retained as a bounded miss. These were corrected after
measurement with regressions observed failing before the fixes. The focused
Python suite then passes 19 tests, and the complete suite passes 99 tests with
the Java 25 toolchain. Both untouched originals still verify with the hardened
observer (`complete=false` for run1; `complete=true` for run2). A relocated copy
also verifies; removing model binding or changing its restore input is rejected
even after refreshing its file-integrity manifest. No measured runtime, input,
result or timing was replaced by these observer corrections.

Review boundaries remain explicit: this finite public experiment establishes no
general CAS superiority, novel discovery, sealed-holdout generalization, or result
for NOT_MEASURED tasks. Shared-host timings do not establish a precision ranking.
The author ran the full Java reactor; the independent reviewer did not repeat
that entire reactor. Retained runtime/package hashes check the declared bytes,
not a whole-OS image or adversarial proof of authorship. Normal library caches
remain enabled to avoid imposing extra cache-clearing work on the CAS.

With Java 25, Maven and Python venv support, reproduce a new observation using:

```sh
bash gradle/run-historical-sympy-comparison.sh
```

Verify an extracted original attempt without executing either competitor:

```sh
PYTHONPATH=scripts python3 -B -m external_polynomial_comparison.run_historical \
  --verify --output /absolute/extraction/run2/evidence
```

The original failed attempt is `run1/evidence` and must report `complete=false`.
Existing workflows, historical protocols, defaults and thresholds are unchanged;
this opt-in measured experiment is reproducible from the checkout. Unit and
process-ownership tests use the existing external comparison test suite.

The release review additionally binds recorded worker commands to the retained
ordered classpath, Python bootstrap/interpreter, frozen JVM options and original
setup paths, and checks the exact lifecycle rotation. Reusing an evidence or
command-receipt path is rejected before launching; completed measurements are
never overwritten, including concurrent invocations. The complete comparison suite passes 113 tests with Java 25,
and both original attempts still pass offline verification after these changes.

## Remaining historical scope

The Euclid/Bézout historical discovery/proof task, Euclid's infinitude-of-primes
proof discovery and the general Ferrari method are **NOT_MEASURED** here: no
shared executable historical task contract is implemented in this comparison.
That does not say their numerical subalgorithms are absent from either library.
The results above do not establish parity for those tasks or all historical
mathematics, global completeness, or an automatically invented proof language.
