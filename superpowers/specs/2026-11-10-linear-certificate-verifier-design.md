# Complete exact linear solution certificate verification

## Intent and acceptance

Close the remaining compact representation-verifier gap with a usable Java API
which checks the complete rational affine solution set from the original source
and retained elementary operations. It must not run a second solver. Preserve
the existing full audit as an independent qualification oracle and preserve
historical execution artifacts and work totals.

This is an implementation choice under the request to continue the comparison
follow-up. It is not a claim of a measured learning or runtime improvement.

## Approach

Use typed `ExactRrefReduction.RowOperation` values already constructed by both
solving routes. Export DIRECT's internal typed operations without parsing its
display strings, and retain each planner part's original row mapping. One
certified execution produces the legacy result and its evidence together.

The verifier independently extracts exact affine coefficients from source,
retaining the syntactic variable inventory. It replays invertible operations
on full augmented rows, checks coefficient RREF up to row permutation, derives
coefficient and augmented ranks, and checks original residuals and the complete
canonical particular/nullspace family. Identity coordinates on every free
column establish independence and rank-nullity establishes completeness.
A source-bound replayed zero-coefficient row with nonzero RHS proves
inconsistency. Full augmented RREF is not required: DIRECT's valid inconsistent
form need only reduce coefficient columns.

Residual-only verification was rejected because it cannot establish completeness
or uniqueness. Minor/inverse certificates would require new producer evidence;
typed row operations already cover rectangular, singular and inconsistent cases.

## Contracts

- Java 25 in existing math modules; no new ULR module or Python mathematics.
- Add immutable typed certificates and certified-execution wrappers. Existing
  `Result`, `Step`, v1 JSON, `solve`, `execute`, `verify` and `audit` retain their
  existing value and semantic contracts.
- Bind all source rows exactly once and all syntactic variables in sorted order.
  Preserve cancelled coordinates and constant contradictions.
- Reject unsupported/nonlinear/undefined input, invalid operations, forged
  rank/classification/basis data and incomplete row coverage.
- Bound source, certificate and rational traversal work. Export/copying and
  every verification stage have explicit costs; budget exhaustion cannot verify.
- Expose semantic verification separately from full execution-artifact replay.
  This API does not validate the producer's claimed route or work ledger.
- Retain old full audit for differential qualification. Any new work metric has
  an explicit version. No default learning-policy change without measured
  non-regression under a separately versioned protocol.

## Validation

Exercise DIRECT, MATRIX, BLOCKS and AUTO over unique, redundant, underdetermined,
rank-zero and inconsistent rational systems, including interleaved blocks.
Attack source/row binding, incomplete or dependent bases, noncanonical families,
invalid rank forms, false contradictions and operation ordering/multipliers.
Check zero/exact/exhausted budgets and rejected domain boundaries. Keep existing
artifact identity/replay regressions. Record construction, certificate export,
compact verification and full audit separately in deterministic qualification.

Dense replay remains cubic in the worst case; rational bit complexity and wall
time differ from mechanical work units. Symbolic coefficient parameters and
nonlinear systems are outside this exact affine contract.
