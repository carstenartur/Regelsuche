# Evidence-preserving native factorization improvements

## Scope

This change integrates the native part of the local univariate comparison work
onto main `e673c6c89bca00f68ca47b8df2a7d20203089b29`. It does not change the
learning-search comparison documented in `external-polynomial-comparison.md`,
select a production learning policy, or execute any protected final study.

## Running work totals without rebuilding evidence

`PolynomialWorkBudget` already maintains a checked running total. The new
package-private `totalWorkUnits()` accessor returns that value directly.
Hensel step deltas, suitable-prime attempt deltas, and remaining-work calculation
use it instead of building, sorting, validating and copying a complete immutable
ledger merely to sum its entries again.

The immutable ledger is still created at evidence boundaries. No stage entry,
mathematical verification, certificate hash, limit, or rejected-budget condition
is removed. Previously created ledger snapshots remain immutable. The test
covers initial/updated totals, zero work, invalid entries, the exact long ceiling
and rejection without mutation.

## Nonzero scalar decompositions

A nonzero constant over the exact integer or rational coefficient domain can
now produce its exact scalar content, an empty list of nonconstant factors and
a unit remainder. In Z[x], completeness here concerns nonconstant polynomial
factors; the scalar content is not certified prime or required to be a unit of Z.

`FactorizationEngine.Proposal` permits this shape only for the two concrete
Z/Q domains with remainder one. It is still an untrusted proposal. The normal
`FactorizationVerifier` must reconstruct and compare the exact source, and an
independent-completeness request retains the charged candidate check and a
verifier-issued trace. No nonexistent factor irreducibility traces are invented.
Wrong scalar content, a nonconstant source masquerading as scalar content,
zero, an unresolved remainder, malformed hashes and exhausted budgets remain
rejected. Other coefficient domains retain the nonempty-proposal contract.

The verified expression renderer also handles scalar one. The existing
transformation layer can continue to discard identity transformations: a
complete answer is not necessarily a new useful rewrite edge.

## Structural budget is not algorithm support

The regression test forms the product of `(x-k)` for k=1 through 17. A request
with degree bound 16 remains inconclusive with `MAX_TOTAL_DEGREE_EXCEEDED`.
An explicit degree bound 32 factors it and independently checks all 17 factors
under the same 20,000,000-unit work and 250,000-candidate limits.

No default bound is raised by this core change. This demonstrates an already
available native algorithm under explicit admission, not learned knowledge or
a new factorization method. The separately retained local comparison adapter's
opt-in degree32 profile is not substituted for the historical degree16 profile.

## Tests and qualification

Six native regression groups are called from the existing JUnit
`NativeUnivariateFactorizationEngineTest`. The underlying checks can also be
executed as `NativeFactorizationContractChecks` without a JUnit launcher; they
throw explicit assertions, not disabled-by-default Java `assert` statements.

Normal repository qualification includes, for example:

```sh
./gradlew :regelsuche-math-algorithms:test \
  --tests de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngineTest
```

Before publication on 13 September 2026 the unchanged patch was freshly
compiled and its six real-engine groups passed locally. The separately retained
native comparison adapter checks and all 24 Python contract/integration tests
also passed. That environment is Java 21.0.11 and a partial source reconstruction
from the digest-verified SDK/source artifacts, with four existing Jackson
annotation class files used solely for compilation. It is not a Java-25 product
build, a dependency-resolution test, or a replacement for normal full CI.

The native source files changed here were unchanged between historical main
`8afeda1a538c6726b0f6b8a6c8014dfcced0fbe2` and the integration base. Uploaded source
blob identities must be checked against the freshly tested files. Full current-
head Java-25 Gradle, Maven/product/Docker, SymPy, JMH and converged ciCheck remain
required before merge. No gate, historical baseline or test exclusion is relaxed.

## Performance claim boundary

The earlier local comparison measured 36 main tasks and four boundary cases,
using SymPy 1.14.0 `Poly.factor_list()` as the native factorization reference.
Those retained Java-21 runs are development evidence, not measurements of the
current Java-25 integration build or the whole Workbench. Regelsuche also emits
internal evidence that the compared SymPy return value does not contain.

This integration does not infer a new speedup from a passing test. New product
performance claims need a separately retained, exact-build-bound measurement.
