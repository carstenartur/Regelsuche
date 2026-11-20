# Bounded native polynomial proof replay

## Intent

Advance the independently checked proof-object requirement in #1066. Extend
the existing Java Z3 equality replayer to compound real polynomial terms and
the Boolean proof structure needed by actual retained Z3 objects. Preserve the
typed `SolverIr` statement and the original retained-attempt authority boundary.

The user authorized continuing issue implementation, verification and green PR
integration. The bounded fragment and its limits below are implementation
decisions, not a claim that all of #1066 is complete. Issue #1066 remains open.

## Existing behavior and observed input

`Z3EqualityProofReplay` currently accepts symbol equalities, exact asserted
leaves, symmetry, ordered transitivity and final unit resolution. Its caller
`RetainedAttempt.replaysEqualityProof(expected)` additionally binds the exact
obligation and retained proof hash to a confirmed external execution.

Fresh Z3 4.8.12 captures for distributivity require real-term `?x` bindings,
Boolean equality/negation, `rewrite`, `monotonicity`, `trans` and `mp`. Decimal
coefficients appear as constant quotients; integer coercions use `to_real`.
Default conditional multiplication captures omit premise leaves and instead
contain condition-dependent rewrites. Those captures must remain rejected.
The official [Z3 proof-rule descriptions](https://z3prover.github.io/api/html/group__capi.html)
define the rules; a rule name alone never authorizes its conclusion.

## Approach

1. Reuse the existing typed `SolverIr.Expression` for real terms. Add only
   private/package-local Boolean proof syntax and bounded wire parsing.
2. Extend congruence, reflexivity and exact Boolean modus ponens. Every changed
   argument must have its matching equality proof; no unrelated dependency may
   be added merely to satisfy the all-premises check. A premise-bearing proof
   must cover a changed argument position not already covered by an earlier
   proof. Unchanged or already covered positions cannot alone justify its
   dependencies. Dependency-free coercion reflexivity remains permitted.
   This local relevance rule does not establish global premise minimality.
3. Check assumption-free polynomial rewrite leaves with the existing
   `PolynomialNormalFormSolverBackend`. Boolean rewrites use explicit logical
   identities and checked equality equivalences. A polynomial nonidentity is
   not a proof that the two expressions differ for every valuation.

Alternatives considered: trusting additional native rule names would weaken
the checker; implementing all SMT theory lemmas, quantifiers and lemma discharge
would greatly exceed this bounded change. Neither is part of this implementation.

## Contract

- Public entry points and external result capabilities stay as they are.
- Assertions bind exact original premises and the exact negated goal; only
  exact numeral spellings/coercions are normalized. Algebraic equivalence does
  not substitute a different asserted formula.
- Support declared REAL symbols, exact decimal literals, addition, subtraction,
  multiplication and positive integer powers with exponents 1–20. Reject
  exponent zero because native Z3 `^` does not establish `0^0 = 1`; the
  polynomial backend's convention cannot silently authorize that native term.
  Constant
  quotients are accepted only when their exact value has a terminating decimal;
  symbolic or nonterminating division remains unsupported.
- Keep term, formula and proof namespaces distinct. Multi-binding lets are
  parallel; duplicate, shadowed, forward and cyclic bindings are rejected.
- Require a derivation of false using the exact negated goal and every distinct
  supplied premise. Preserve rejection of duplicate/unused premises.
- Keep the 128 KiB object bound, 8,192 wire nodes and depth 128. Bound expanded
  terms/formulas, polynomial expansion and total leaf checks before expensive
  equality/hashing/normalization. Exhaustion returns no independent replay.
- Before normalization, conservatively bound coefficient numerator size and
  a common denominator to at most 4,096 bits each. Propagate these estimates
  through constant coefficients inside nonconstant terms as well as purely
  constant expressions. Admission need not exploit later cancellation or
  reduction. These are conservative bounds, not exact work measurements.
- No external process, approximate arithmetic or new third-party dependency
  enters the replayer. Unsupported theory rules remain rejected.

## Verification and acceptance

Observe failing tests on the old checker, then green tests for captured native
polynomial objects and exact congruence objects. Generate fresh native objects
through the existing retained backend and run the checked-proof CI lane.
Exercise altered coefficients/operators, wrong argument order, invented or
unused premises, mismatched goals, false Boolean rewrites, undeclared symbols,
wrong namespaces, let scoping and bounded-expansion failures. Preserve the old
symbol-chain and retained-file substitution controls.

The final review of `34447245` additionally requires regression controls for
zero powers, coefficient growth hidden inside nonconstant terms and redundant
premise-bearing congruence arguments. Retain positive exponent-boundary and
dependency-free coercion controls. Verification of the corrected implementation
must be recorded separately from the earlier passing tests.

Update the proof-bridge documentation and research-strategy index with actual
evidence and the explicit remaining #1066 scope. Require green applicable PR
checks and exact integrated-tree verification before reporting integration.
