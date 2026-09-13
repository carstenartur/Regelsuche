# Bounded recurrence-invariant discovery and induction

The native #874-F operation searches polynomial invariants of an explicitly
supplied rational homogeneous linear recurrence. It uses
`ExactLinearPolynomialHoleSolver`, its exact RREF backend and the existing exact
polynomial checker. It does not infer another recurrence from a numeric prefix:
`LinearRecurrenceSequenceDomain` already does that exactly and its evidence
remains finite-data validation.

## Frozen source and finite search

`RecurrenceInvariantFormation` defines the recurrence convention

\[
a_{n+k}=\sum_{i=0}^{k-1}r_i a_{n+i},\qquad
s_n=(a_n,\ldots,a_{n+k-1}).
\]

Exactly `k` rational initial values are required. Formation also binds the
consecutive shifts `0..k-1`, a nonempty distinct homogeneous monomial basis,
its degree, a finite exact rational lambda domain, coefficient/intermediate-bit
limits, per-solve and total work limits. No historical name, target, expected
invariant, later sequence values or reference answer is an input field.

The first fragment permits order one through three, degree one through three,
at most twelve basis terms and sixteen lambdas. `homogeneousBasis(order, degree)`
generates the full finite homogeneous basis before solving; an explicit subset
can also be frozen. All state, coefficient-hole and normalization symbols are
generated from disjoint fixed namespaces and integer coordinates. Caller text
cannot capture a state or coefficient variable. Nonempty assumptions produce
`UNSUPPORTED` without solver work. Assumption text rejects unpaired UTF-16
surrogates before freezing, so UTF-8 replacement cannot identify a different
negative context as the same run; valid supplementary characters remain exact.

For each frozen lambda and each possible first nonzero coefficient index, the
search derives the polynomial equation

\[
I(T(s))-\lambda I(s)=0
\]

from the companion transition `T`. A chart sets the chosen coefficient to one
and its predecessors to zero. Independent marker variables encode these linear
normalization equations alongside the transition equation in the existing
affine polynomial solver. They are not recurrence state variables. This excludes
the zero polynomial without choosing a historical coefficient vector.

The native solver derives and solves every coefficient equation. A unique
normalized vector outside the frozen numerator/denominator bit bound is retained
as `COEFFICIENT_BOUND` with its actual solver result. A nonunique chart stays
`UNDERDETERMINED`; no free parameter is chosen. Inconsistency, unsupported
projection, work exhaustion and independent check failure remain distinct.

Complete status refers to the frozen lambda/chart language only. A retained
underdetermined or resource-limited chart makes the run incomplete, even if
another chart supplied a checked invariant. Unstarted charts are explicitly
counted. No completeness over all polynomials, rational eigenvalues, nonunique
eigenspaces or larger coefficient bounds is claimed.

## One work authority

Formation rendering, native solving, actual solver replay, transition checking
and initial-value checking share one cumulative remaining budget. Generated
expression fragments are admitted before append; delegated solver ceilings are
admitted before calls. Each attempt receives at most the declared per-solve cap
and half the then-remaining budget to leave room for replay. Rebuilding the
expected template and independent checks consume the same authority and may
still exhaust it. Failed attempts do not reset the budget.

The private replay witness carries that exact authority into induction checking;
the checker cannot receive an unrelated fresh counter. The complete native
stage profiles are retained alongside the aggregate. Their original and replay
work sums agree with the corresponding aggregate stages.

Input rational bit bounds are checked before native work. The configured scalar
bound governs the derived matrix/RREF and initial-value arithmetic; initial
products and sums use conservative bit-room checks before `BigInteger`
arithmetic. Native source projection and the independent transition checker
retain their existing fixed 4,096-bit, expression, syntax and expansion limits.
These declared operation counts are not CPU time or an assertion that hashing,
formatting and every allocation are counted.

## Specialized induction authority

`ExactRecurrenceInductionVerifier` first binds the native result to the exact
formation, chart, generated template and limits, then really reruns the native
solver. Complete concrete result equality includes every coefficient constraint,
RREF operation, certificate field and work profile. Only that gate can issue the
private replay witness.

The induction checker reconstructs the concrete polynomial and companion
transition from the typed recurrence and coefficient vector. It uses repeated
multiplication and separately built companion expressions rather than the
solver's instantiated expression or alleged normal form. The existing exact
polynomial analysis checks the resulting full transition expressions. Initial
evaluation independently multiplies the supplied rational initial values through
each declared basis term under the same work/bit limits.

The resulting sealed `VerifiedInduction` establishes exactly

\[
I(s_n)=\lambda^n I(s_0)\quad\text{for every integer }n\geq0
\]

under the bound recurrence and initial data. The fixed induction rule combines
the universal checked transition with the checked base value. Natural powers
have `lambda^0 = 1`, including `lambda = 0`; no division by lambda or extension
to negative indices occurs. This is a specialized certificate checker, not a
general quantified logic, formal proof-assistant export or an expanded proof
DAG language.

A public `Certificate` is only retainable data. Independent verification receives
the expected formation, certificate and a bounded verification allowance; it
replays the native solve, repeats transition/initial checks and requires complete
certificate equality. Altered initial data, full rehashes, a replacement
transition expression, zero coefficients or a foreign native source cannot
issue the sealed capability. Each independent verification reports its own
actual work; it does not erase the retained discovery run's work.

## Public execution and correspondence boundary

```java
var formation = new RecurrenceInvariantFormation(
    new RecurrenceInvariantFormation.Recurrence(
        List.of(ExactRational.ONE, ExactRational.ONE),
        List.of(ExactRational.ZERO, ExactRational.ONE)),
    List.of(0, 1), 2,
    RecurrenceInvariantFormation.homogeneousBasis(2, 2),
    List.of(ExactRational.integer(-2), ExactRational.NEGATIVE_ONE,
        ExactRational.ZERO, ExactRational.ONE, ExactRational.integer(2)),
    List.of(), new RecurrenceInvariantFormation.Bounds(128, 256, 20_000, 200_000));
var run = new ExactRecurrenceInvariantDiscovery().discover(formation);
String completeCandidateFreeze = run.toCanonicalJson();
String freezeHash = run.contentHash();
// Historical correspondence is a separate subsequent consumer of this freeze.
```

Canonical run bytes include formation, all attempts and negative outcomes,
original/replayed constraints and RREF evidence, work profiles, checked
certificates and unstarted-chart count. Certificate bytes contain the full
native result and transition/initial obligations. The additive export ceiling
is 16,000,000 UTF-8 bytes; oversized exports fail explicitly. Existing finite and
linear v1 artifact algorithms are unchanged. This native slice offers canonical
export and a typed independent certificate verifier, not a new generic runner,
serialized artifact loader, default runtime registration or module dependency.

The public characterization freezes all fifteen charts and their checked
candidate before comparing its representation to the public Cassini identity.
Additional native controls cover a different recurrence, lambda zero, nonunique
charts, omitted lambda, coefficient exclusion, forged/rebound evidence and
work/initial-bit exhaustion. Names and expected representations are inspected
only after the complete freeze. This is an ordinary reproducible public test,
not a confidential historical holdout, external preregistration timestamp,
new mathematical result, learned tactic or novelty/promotion evidence.

Broader shifts, nonhomogeneous or nonlinear recurrences, nonunique coefficient
families, automatic grammar learning, generic discovery/search integration and
scientific held-out comparison remain separate work. No protected study or
production promotion is performed here; #874 remains broader than this fragment.
