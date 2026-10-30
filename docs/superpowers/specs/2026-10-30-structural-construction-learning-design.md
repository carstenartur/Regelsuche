# Bounded additive and multiplicative construction in task learning

## Approved intent
Connect existing preparation/composition capabilities to cumulative task learning.
Support both additive and multiplicative auxiliary construction, not historical
answer rules. Keep independent mathematical checking, explicit assumptions,
finite budgets, stable generations, and the same improved baseline.

## Design
1. A typed finite `AlgebraicConstruction` API constructs additive complements and
   partitions, multiplicative complements and partitions, and factor transfers.
   The auxiliary is an expression, not an unbound pattern variable. Its domain is
   bounded scalar rational expressions. Existing rational-equivalence checking
   must reject multiplication/division by zero and missing nonzero premises.
2. `StructuralTaskObjective` gives factorization and sums of squares stable,
   versioned task identities. Structural evidence alone is never equivalence.
3. Opt-in `TaskConstructionProfile.STRUCTURAL_V1` augments the EXISTING typed
   frontier with existing square exposure, completion, factorization and exact
   residual composition. Candidates are source-only, regenerated and independently
   checked; no named historical answer is installed. Raw neutral insertion is an
   explicit diagnostic/construction API, not unbounded default branching.
4. The same capability profile is available to baseline and learned arms.
   New snapshots bind the profile; old v1 snapshots/default behavior stay intact.
5. In the opt-in profile, independently replayed improving partial paths and
   algorithmic construction steps can supply examples to the existing schema
   generalizer. Single already learned applications alone remain non-novel.
6. Work receipts include generation and repeated verification. Structural limits
   bound atomic work; an exceeded query budget is never reported as solved.
   These are declared logical receipts, not complete CPU/heap accounting.

## Out of scope
No noncommutative matrices, radicals/branch identities, universal induction,
unbounded auxiliary generation, or claim of autonomous strategy invention.
The residual program is an existing declared strategy; learning its generated
transformations is not learning that strategy from nothing.

## Acceptance
Real production tests must cover exact rational and large-integer arithmetic,
zero/symbolic divisor rejection and explicit-premise admission, useful product
construction, historical source-only solutions, reusable learned schemata,
partial-progress learning, immutable restoration and unchanged old regressions.
No CI quality gate, baseline fixture or original diagnostic is weakened.
