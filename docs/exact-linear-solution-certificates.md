# Complete exact linear solution certificates

`ExactLinearSolutionVerifier` checks the complete rational affine solution set of
bounded source equations without running another solver. The proof contains
immutable typed elementary operations and an ordered source-row map for each
part. DIRECT exports its internal typed operations during the original execution;
MATRIX and BLOCKS retain the operations already produced by exact RREF.

```java
var planner = new LinearRepresentationPlanner();
var source = List.of("x+y=1");
var certified = planner.solveCertified(source,
    LinearRepresentationPlanner.Route.DIRECT, 100_000,
    new RepresentationBridge.Budget(100_000));
var checked = new ExactLinearSolutionVerifier().verify(source,
    certified.result().solution().orElseThrow(),
    certified.certificate().orElseThrow(),
    new RepresentationBridge.Budget(100_000));
assert checked.verified();
assert checked.ranks().orElseThrow().coefficient() == 1;
```

Applications must inspect construction status and certificate presence before
using the payload. `executeCertified` accepts an existing prepared plan. These
additive APIs return the same legacy `Result` as `solve` / `execute` with the same
construction budget. The additional certificate budget pays for retention and
export. Its status is `EXPORTED`, `BUDGET_INCONCLUSIVE`, `CERTIFICATE_LIMIT`, or
`NOT_SOLVED`; unsuccessful export leaves the mathematical result intact.

The checker returns `VERIFIED`, `INVALID_CERTIFICATE`, `DOMAIN_UNSUPPORTED`, or
`BUDGET_INCONCLUSIVE`. Only `VERIFIED` includes derived coefficient and augmented
ranks. Budget exhaustion never verifies, including at intermediate stage
boundaries. A zero budget performs no charged work. Malformed source is metered
before rejection; unsupported affine expressions and source rational-size limits
are domain boundaries. Oversized proof rationals are invalid evidence.

## Proof obligation

The checker independently parses and evaluates affine coefficients. It discovers
the sorted **syntactic** coordinate inventory before simplification, preserving
cancelled variables. Constant rows remain in the system. Every source occurrence
must occur exactly once in the parts' row maps. Local operation indices are
mapped to source rows, and operations act on all global coefficient columns and
the right-hand side, regardless of the claimed partition.

Invertible replay preserves the solution set. A structural scan requires every
nonzero coefficient row to have a leading one whose column is zero in all other
rows. Pivot rows may be interleaved. This proves exact coefficient rank without
pivot search or another elimination. A coefficient-zero row with nonzero RHS
proves inconsistency and increases augmented rank by one. Full augmented RREF is
unnecessary: DIRECT may retain several contradiction rows and nonzero RHS
entries above them.

For consistent systems, the checker requires exactly `n - rank` basis vectors,
zero free coordinates in the particular solution, and the identity matrix on the
ascending free coordinates of the basis. It checks every original row residual
for the particular point and every basis vector. Residuals establish membership;
identity free coordinates establish independence; rank-nullity establishes
completeness. These constraints also fix the existing canonical family.
Residual-valid incomplete, dependent, shifted, or rescaled families fail.

The API proves solution semantics relative to the supplied source. It does not
validate route selection, producer work, display strings, or execution history.
Existing `verify(Result)`, the independent full `audit`, v1 JSON, and historical
learning protocols are unchanged. No certificate persistence codec or automatic
learning-policy adoption is included.

## Bounds and work versions

Source has 1–16 equations, each at most 512 characters, at most 16 syntactic
variables, depth at most 64, numeric literals at most 128 characters, decimal
exponents of magnitude at most 64, and the existing nested power-weight limit
of 64. The supported fragment includes affine addition/subtraction, products
with a normalized constant operand, division by a nonzero constant, exponent one,
and constant integer powers of magnitude at most 64. Variable powers such as
`x^0`, functions, nonlinear products, variable divisors, and undefined zero
powers fail closed. Constant-only systems remain outside the planner's nonempty
coordinate contract; constant rows within a system are retained.

Proofs have at most 16 nonempty parts and 4,096 total operations. Inspected
rationals have numerator and denominator bit lengths at most 16,384. An arithmetic
operation can temporarily produce at most roughly twice that many bits before
its result is checked. These explicit limits bound traversal and arithmetic
operands; they do not turn mechanical work units into CPU or bit-operation costs.

`regelsuche.linear-semantic-work/v1` reports five stages:

| Stage | Charged work |
| --- | --- |
| Source binding | Initial shape check, source row checks, text characters (bounded parsing and syntax validation allowance), AST visits, variable-name characters, affine vector initialization/scans, rational arithmetic |
| Proof validation | Statement/schema check, variable binding, parts, row-map entries, coverage scan, operation validation and multiplier inspection |
| Replay | Original coefficient/RHS copy inspections, operation dispatch, row swap, each rational row update including RHS |
| Rank | Each coefficient scan, pivot-column scan, and zero-row RHS inspection |
| Consequence | Classification check, free-coordinate scan, canonical coordinates, every multiplication/addition in each original residual, and residual comparisons |

A rational inspection costs `2 + ceil(max(numeratorBits, denominatorBits)/32)`.
Arithmetic costs one unit plus inspection of every operand and the result;
negation has one operand. Consequently an entire dot product is never one unit.
Constant-size shape and index checks are bundled with the surrounding charged
record/coordinate visit. A failed charge consumes the remaining budget and
returns inconclusive. Collection constructors make defensive immutable copies;
externally constructing a supplied certificate is outside verifier work.

`regelsuche.linear-certificate-export-work/v1` separately charges:

- Beginning a part: one unit plus its source-row count.
- Each typed operation: one unit for retention/conversion, then one plus the
  multiplier's maximum bit length rounded up to 32-bit words.
- Finishing a part: one plus its row count plus its operation count, covering
  immutable list retention.
- Finishing the certificate: one plus its part count.

Legacy construction and full audit retain their historical v1 accounting.
Their units are **not** the new checker's rational-traversal-weighted units.
New totals must expose those components separately and include export work;
qualification runs additionally pay for the full audit.

## Deterministic qualification

`ExactLinearSolutionVerifierTest` qualifies 30 generated rational systems with a
fixed seed across DIRECT, MATRIX, BLOCKS, and AUTO (120 executions), comparing
against the unchanged independent full audit. Explicit fixtures additionally
exercise redundant, rectangular, rank-zero, inconsistent and interleaved systems,
16 cancelled coordinates, all stage boundaries, export exhaustion, malformed
maps, altered operations, incomplete and dependent families, and domain limits.
The qualification test prints construction, preparation, execution, composition,
export, checker stages, and full-audit costs separately.

Dense operation replay remains cubic in the worst case. Independent dense source
normalization and full-width block replay can cost more than an alternative
solver's historical units. No measured speedup, default-policy improvement, or
cost non-regression is claimed by this feature. Full audit remains the
qualification oracle and default learning behavior is unchanged.
