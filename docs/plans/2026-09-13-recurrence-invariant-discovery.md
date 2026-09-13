# Bounded recurrence-invariant discovery

Approved ordinary #874-F implementation on
`37bd83221370631653af3c58a6a20edefe23dbf7`.

Source audit: `LinearRecurrenceSequenceDomain.inferForOrder/solveUnique` already
fits exact recurrence coefficients to finite prefixes and explicitly does not
prove infinite continuation. `ExactLinearPolynomialHoleSolver` supplies bounded
affine coefficient projection, native RREF and independent polynomial identity
checking. `ExactResidualPolynomialArithmetic` supplies a work-admitted exact
projection without changing legacy consumers. No existing induction checker
establishes a recurrence invariant.

1. Freeze a typed rational homogeneous recurrence and its initial state, a
   homogeneous finite monomial basis over state shifts, finite rational lambdas,
   coefficient/intermediate-bit and total/per-solve work bounds. No historical
   name, target or reference-answer input is accepted.
2. Derive `I(T(s)) - lambda I(s) = 0` from the companion transition. Enumerate
   only the frozen lambda and first-nonzero-coefficient normalization charts.
   Reuse the native linear hole solver; retain unresolved/failed charts.
3. Charge formation, actual native solving and replay, transition and initial
   checking to one remaining work budget. Admit delegated limits before calls.
   Keep complete solver and certificate objects, not hash-only authority.
4. Issue a specialized checked induction certificate only after reconstructing
   the original transition and initial value independently. Its entire claim is
   `I(s_n) = lambda^n I(s_0)` for `n >= 0` under the bound recurrence/initial data,
   including the `lambda = 0`, `n = 0` case. No logical proof DAG is implied.
5. Test native discovery, false/foreign source and certificate binding, zero and
   underdetermined candidates, lambda zero and cumulative work exhaustion.
   Freeze complete public candidates before checking historical correspondence.
   Compare old canonical samples byte for byte and run focused offline Maven.

The first fragment has order at most three, homogeneous degree one through
three, at most twelve basis terms and sixteen lambdas, empty assumptions and
unique normalized charts only. This adds a typed native mathematical operation,
not another Discovery runner or module dependency. No protected study, default
activation, publication, historical holdout or novelty claim is authorized.
