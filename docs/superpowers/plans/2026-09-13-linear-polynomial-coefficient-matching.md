# Exact linear polynomial coefficient matching

## Source-informed scope

Issue #874 C still lacks rational linear coefficient matching. The finite solver deliberately
enumerates caller-frozen values. The separate numeric recurrence-prefix domain already performs
exact rational elimination; symbolic recurrence invariants and closed forms do not yet have a
production orchestration path. This slice implements C, not a universal recurrence solver.

Add a separate `ExactLinearPolynomialHoleSolver` receiving a source, frozen coefficient template,
declared holes, explicit assumptions and limits. Use the existing parser-issued exact polynomial
projection to derive one rational equation per source/template monomial. Reject nonlinearity in
the formal holes, variable denominators and nonempty assumptions. Reuse `ExactRrefSolver` through
an additive raw-matrix entry which derives its own ranks and validates dimensions and arithmetic
bit room. Emit a candidate only for a unique solution independently checked by the existing
`ExactPolynomialAnalysis.requireEquivalent` against the fully instantiated original template.

The new result identity binds all inputs, limits, derived system, reduction, candidate and work.
Inconsistent, underdetermined, unsupported and budget-inconclusive outcomes retain no candidate.
Projection, constraint construction, elimination with row-operation replay and final checking
share a non-resetting work budget. Existing finite enumeration, RREF behavior and v1 evidence
remain unchanged. No executable proof-plan or production-promotion authority is fabricated.

## Implementation and verification

- [x] Add failing public controls for a rational solution outside a frozen finite domain, coupled
      equations, inconsistency, singularity, nonlinear holes, assumptions, guards and budgets.
- [x] Add raw-matrix admission and bounded arithmetic to the existing RREF implementation while
      retaining the original API's exact result behavior.
- [x] Add the linear template adapter and independent complete-template identity check.
- [x] Retain versioned, deterministic complete and negative results; prove tampered candidates
      cannot pass replay.
- [x] Run the new controls and affected existing polynomial/RREF/finite-plan checks with isolated
      offline Maven, then commit with a local verification receipt.

No protected input, historical target, holdout or test endpoint participates in candidate formation.
No Gradle, API mutation, default activation or frozen original evidence changes are authorized.

## Retained local verification

The fresh offline Maven run passed 93 tests in 13 suites: ten new controls and 83 existing
mathematical/finite-plan controls. The initial absent-API compilation failure is retained separately
from behavioral REDs. Actual behavioral REDs exposed missing nested row/certificate identity,
resource-exhaustion misclassification and omitted charging of raw-system back-substitution; the
current tests reject those failures. A real frozen 49-value-pair enumeration has no solution for
the coupled public example, while elimination derives `104/77` and `83/77` and independently checks
the entire instantiated template.

Original source files extracted from base `1388aeec853b60c0e9f9b1a47fc5781693aad570` were compiled
separately for the v1 comparison. Four finite runs, all three RREF classifications plus exhausted
work, exact-analysis observer counts and exact/domain/projection controls produce 11,087 identical
bytes (SHA256 `3cbebad8e3224aedcd32014ec4357947a8f577c64e8d79937cc38d2f8dbd8fe2`). This bounded
comparison does not claim exhaustive equivalence for every possible input. The exact selectors,
logs, source probe and comparison receipt are retained in the worktree's local review directory.

Independent code review and full integrated CI remain required. New linear-result integration
into the existing finite-only plan evidence/authorization chain, recurrence-invariant orchestration
and broader tactic/proof work remain ordinary implementation tasks; protected study execution is
a separate later gate. No completion of #874 or empirical transfer/gain claim is made.
