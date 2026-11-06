# Native polynomial simplification

Implement the expression-quality goal agreed with Carsten: close the arithmetic,
grouping, expansion and factorization gaps exposed by the existing 24-expression
SymPy comparison. Reuse the existing typed frontier, exact arithmetic, polynomial
normalizer, native univariate factorization and independent polynomial checker.

The new opt-in `POLYNOMIAL_SIMPLIFICATION_V1` task profile supplies general native
candidate procedures to both primitive and learned task solvers. Source-only
simplification retains the original expression on ties and explores the same
frontier as task completion. No external CAS, expected answer, case identifier or
second search implementation enters candidate generation. Existing profiles and
frozen comparison protocols retain their semantics.

General candidates cover local exact canonicalization, expanded normal forms,
common-factor grouping independently of association/order, and bounded native
univariate factorization. The profile enforces the existing total rational
polynomial domain and independently verifies every edge and final witness.
Variable denominators and unsupported syntax cannot become valid by cancellation.
Failed candidate generation, normalization, factoring, replay and selection remain
charged. Logical work is reported separately from time, CPU and memory.

The implementation adds source-only optimization to the prepared task solver and
profile-aware factory overloads. Existing cumulative knowledge can use the same
persisted profile for solving, learning and restoration. Historical APIs remain
unchanged by default.

Acceptance: independently equivalent outputs, at most the fixed SymPy portfolio's
arithmetic-operation cost on every historical expression, preserved controls and
explicit budget outcomes. A new declared comparison uses the historical inputs
and additional coefficient/grouping/composition variants, with rotated repetitions,
paid training/restoration and process/audit costs. New variants are withheld from
implementation tests until the first evaluation; any later tuning is disclosed.
No general multivariate-factorization completeness or universal CAS/time advantage
is claimed. Native result quality and actually measured resource costs are separate.
