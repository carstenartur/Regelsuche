# Golomb–Keller: first general algebra obligations

4 October 2026. First bounded implementation step of #1066. Manuscript base:
Primachsenraum v4 (2 October), Git blob `723603459c381ae827b21107c62fa684c8a99eaa`.
The additions are proposals for v5 review, not an overwrite of the sent v4.

## What this stage proves

`GolombKellerAlgebra` builds native `SolverIr.Obligation` values and executes the
existing `PolynomialNormalFormSolverBackend`. It introduces no new expression
format, search core, arithmetic implementation or evidence strength.

The five identities hold for arbitrary real variables, not merely sampled inputs:

| Enum | Polynomial identity | Manuscript role |
| --- | --- | --- |
| `LOSS_NUMERATOR` | `a*(1-x*x)-a*x*x = a*(1-2*x*x)` | Numerator after clearing the first-difference denominator. |
| `DENOMINATOR_SPLIT` | `2*(1-x*x) = 1+(1-2*x*x)` | Algebraic separation of the denominator from the positive reserve. |
| `PARITY_RESERVE_NUMERATOR` | `5*(1-2*x*x)-(1-x*x) = 4-9*x*x` | Numerator of the comparison with reserve `1/5`. |
| `STABILITY_BUDGET` | `10*78*5*a = 156*25*a` | Cross-multiplied cancellation in `D*eta=d/2`. |
| `GAP_FOUR_FACTOR` | `1-x^4 = (1-x)*(1+x+x^2+x^3)` | Finite factorization used in the gap-four mass envelope. |

Clearing a denominator here **does not prove** it is nonzero or positive.
The identities do not prove convergence of an infinite series, derivative
bounds, primality, limsup statements or the analytic transfer. Those require
separate obligations and declared dependencies.

Each identity has an explicit false right-hand-side-plus-one control, which
must be `REFUTED`. Refutation means the two polynomials are not identical; it
does not mean that two arbitrary distinct polynomials disagree at every point.
Five more controls must be `UNSUPPORTED`: an explicit assumption, division,
an exponential call, an order relation and a requested formal-kernel proof.
The current exact normal-form backend does not advertise those capabilities.
Unsupported goals are not silently weakened into identities it could prove.

## Execution and retained evidence

```sh
mvn -B -pl regelsuche-solver-ir -am \
  -Dtest=GolombKellerAlgebraCampaignTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Normal JUnit runs execute the genuine exact backend. The test creates a fresh
`target/golomb-keller/run-*` directory, retaining all 15 native chains:

```text
<obligation-id>/
  obligation.json
  translation.json
  result.json
  execution.json
```

The exact requested obligation is written before backend invocation. Returned
artifacts are written before status and identity comparisons. A different-goal
reply or execution failure is retained but cannot produce `completed.json`.
Another run uses a new directory and cannot overwrite the first observation.
`FAILED.txt` marks an incomplete campaign; it is never a successful proof receipt.

The backend's certificate hash refers to its exact normal-form result, not to an
exported Lean proof term. These files retain canonical execution contracts for
reproduction. This stage does **not** claim independent replay of a standalone
formal proof object or a human mathematical review. The planned Z3/Lean stage
must retain the full external proof object and its tool/dependency checks.

The initial real Java-25 Maven run `37187064770` compiled the seven-module
reactor and failed exactly the availability test for the missing class. The
implementation's own current CI result is required before claiming this suite
passed; an earlier head or mock backend cannot establish that.

## Beyond current capability

Issue #1066 tracks real inequality proofs, assumption consistency, full proof
artifact retention, a Lean adapter using this same IR, checked target types and
transitive axiom policies, and versioned support for binders/sets/sums/limits.
A successful solver exit is not a complete proof gate. Prime number theorem and
Dusart dependencies must be proved in the chosen environment or remain explicit
imported assumptions. Regelsuche can be extended for those requirements; this
small first stage neither imposes a permanent limit nor pretends the extension
has already been delivered.

This is application of existing exact algebra to hand-specified obligations,
not autonomous discovery, a learned speedup or scientific priority. The
manuscript's infinite-prime results still need analytic and external review.
