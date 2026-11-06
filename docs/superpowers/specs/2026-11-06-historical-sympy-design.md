# Historical task comparison against SymPy

Carsten requested the direct comparison missing from the polynomial-operation
benchmark: Cassini/Pell invariant discovery, Brahmagupta–Fibonacci representation
as two nonconstant squares, and Sophie-Germain nontrivial factorization.

Use the current production APIs at main `33f482850e22fe8e3200c1f0d05a2d5e584450d0`.
This experiment does not change mathematical capabilities or tune them to results.
It is independent of the optional polynomial-simplification PR.

## Method

The user approved comparison with the same starting information and task goals.
My implementation choice is a thin Java adapter over `CumulativeTaskKnowledge`,
an independent Fraction-polynomial judge, and a pinned SymPy 1.14.0 adapter.
Reusing `simplify`/operation count would not measure these goals. Feeding known
historical formulas to either competitor would measure verification instead of
formation. Neither alternative is used.

Both receive only source/goal, or an exact affine transition, initial state and
degree bound. No target polynomial, historical name or reference path is passed
to a query worker. Programmer-supplied procedures and the recurrence ansatz are
explicit capabilities, not autonomously invented strategies.

- Invariants: one nonconstant polynomial of degree at most two, with multiplier
  +1 or -1. Independently verify the universal simultaneous-substitution identity
  and exact base value. Native uses its existing synthesis/reuse APIs; SymPy uses
  exact coefficient comparison and `Matrix.nullspace`, without historical vectors.
- Factorization: exact equality and an explicit product of two nonconstant
  rational polynomials. Native uses `STRUCTURAL_V1`; SymPy uses `factor_list`.
- Two squares: exact equality and an explicit sum of two squares with
  nonconstant polynomial bases. SymPy factors over Gaussian rationals, pairs
  conjugate factors and extracts real/imaginary parts. This is a bounded supplied
  procedure, not a claim of complete sum-of-squares decision capability.

## Frozen experiment

`config/benchmarks/historical-sympy-v1.json` fixes 18 public tasks, lengths
1/18/72 (the last repeats that task list four times), three rotated repetitions
and profiles NATIVE_BASE, NATIVE_LEARNED, SYMPY. There are 27 lifecycles and 819
planned requests, not 819 independent mathematical families. The four canonical
historical tasks are reported individually, separately from variants/controls.
All are public development tasks; there is no sealed holdout or novelty claim.

Query timeout 30 seconds, setup timeout 120 seconds, no warmup or answer cache.
Java uses `-Xmx512m`; process RSS is observed, not falsely equated with a common
512 MiB address-space limit. Native intrinsic logical budgets are reported but
are not comparable to SymPy CPU instructions. Native structural limits are
24 primitive steps, depth 12, 1,000,000 theory units, 128 states, 2,000,000 total
work units. Recurrence requests have a 100,000-unit native budget.

The learned arm actually executes two expression learning blocks, two recurrence
blocks and family formation, saves through the public API, exits, and loads and
reproves in a fresh consumer JVM. The baseline does no unused training. SymPy is
stateless and may ignore the available teaching prelude; no custom SymPy learner
or preinstalled historical identity is added. This compares declared procedures,
not every possible CAS program or the uniqueness of Regelsuche's learning.

## Evidence and reporting

Retain and launch from actual Java and Python worker/runtime bytes, record the
interpreter/JDK identity, bind cases and all request/response fields, models,
setup stages and complete matrices. Reuse `AccountedSession` for wait4 CPU/RSS,
including failed startup ownership. Count acquisition, I/O, fresh startup,
restoration/reproof, transport, independent audit and shutdown in sequence wall
and CPU. Export/snapshot work is outside sequence boundaries and included in a
separate outer command receipt. Missing resources or unequal quality vectors
withhold performance ratios. Preserve unsuccessful attempts and invalid claims;
no win cancels a quality loss. Offline verification must recheck original bytes.

Euclid/Bézout, Euclid's infinitude proof and the general Ferrari discovery/proof
tasks lack a shared executable contract here. List them as NOT_MEASURED rather
than silently counting them as successes or ordinary simplification failures.
Existing historical protocols, defaults, workflows and quality thresholds remain
unchanged. Publish one focused reproducible comparison and its complete evidence.
