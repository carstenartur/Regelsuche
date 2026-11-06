# Checked quartic construction in cumulative tasks

## Intent and scope

Make the existing bounded coefficient-equation factorization engine available
to the existing task frontier and cumulative learner. Allow the existing
residual composition to work on expanded sums as well as products. These are
algebraic
building block toward broader historical discovery, not an implementation of
Ferrari's general solution, autonomous plan invention, or full historical
coverage. The objective is nontrivial verified decomposition, not a promise of
irreducibility or completeness.

## Approach

The existing preparation operators cannot generally expose a product of two
quadratics when all five binary quartic coefficients are present. Brute-force
neutral term insertion would grow the existing search and obscure accounting;
a separate solver would bypass the established evidence and learning path.
Instead, add an opt-in `STRUCTURAL_V2` capability containing the unchanged V1
operators, a bounded adapter to `BinaryQuarticFactorizationEngine`, and the
existing residual composition on additive-root source expressions.

The adapter extracts a bounded, exact two-variable homogeneous integer quartic
from the source, requests candidates from the existing engine through
`FactorizationVerifier`, renders only verifier-issued decompositions, and
submits them to the existing source-bound replay and exact-polynomial verifier.
Extraction, engine, candidate verification, rendering and replay remain paid.
Unsupported inputs and exhausted budgets yield no new theorem. A supported
finite miss is not an irreducibility claim. Existing V1 snapshots and their
provider set retain their original semantics.

## Acceptance

Exercise a quartic with all intermediate coefficients, alpha-renamed transfer,
unfactorable/unsupported cases, exact units, source-bound replay and budget
failure through real production classes. Confirm actual solver use and, where
admitted by the existing benefit gate, learning plus restore. Compare V1 and V2
on the same declared tasks without claiming that this is a broad holdout or
proof of general speedup. Run native Java 25/JUnit tests and CI when available;
report unavailable checks explicitly.
