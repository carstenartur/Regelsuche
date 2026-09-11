# Applicability-schema coverage for safe preparation

**Status: 11 September 2026**

`SAFE_PREPARATION_V2` may prepare a rule only when the rule has an explicit,
reviewable applicability contract. The preparation layer never derives a schema
from a rule ID, Java class name, example, benchmark, or observed execution.

## Eligibility contract

`RewriteApplicabilityCatalog` records one decision for every visible rule:

1. an equivalence-preserving `PatternRewriteRule` with no separately emitted
   assumptions is eligible from its declarative source pattern;
2. every rule with custom applicability/guard semantics is eligible only when
   the concrete rule object implements `RewriteApplicabilitySchemaProvider`;
3. that provider is itself a `RewriteRule`, and its schema must retain the same
   executor object and rule ID;
4. non-equivalence-preserving rules are excluded;
5. every other algorithmic rule remains visible as
   `OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA`.

A `PatternRewriteRule` subclass that emits custom assumptions is fail-closed as
`OUTSIDE_SAFE_PROFILE_UNDECLARED_ASSUMPTIONS` unless it implements the explicit
provider. This prevents a source pattern from accidentally standing in for an
undeclared domain contract.

`RuleDomainRegistry.applicabilityCoverageFor(...)` returns positive and negative
decisions; `applicabilitySchemasFor(...)` returns only the eligible schemas.
Excluding a rule from schema-directed preparation does not by itself remove its
ordinary direct executor.

## Explicit algorithmic schemas in this slice

| Domain | Rules | Required guards |
| --- | --- | --- |
| Trigonometric | `trig_tan_to_sin_over_cos` | `cos(A) != 0` |
| Calculus | `calculus_exp_of_ln` | `X > 0` |
| Calculus | `calculus_ln_of_exp`, `calculus_exp_of_zero` | none |
| Logarithmic | product / quotient split for `log` and `ln` | both arguments `> 0` |
| Logarithmic | power-to-factor for `log` and `ln` | base `> 0` |
| Logarithmic | `log(1)`, `ln(1)` | none |
| Radical | `sqrt(A^2) -> abs(A)` | none |
| Radical | `sqrt(A*B)` split | `A >= 0`, `B >= 0` |
| Radical | `sqrt(0)`, `sqrt(1)` | none |
| Rational | fraction multiplication | both denominators `!= 0` |
| Rational | division by a fraction | inner numerator and denominator `!= 0` |

The tests instantiate every declared guard from retained pattern bindings and
compare it with the concrete executor's assumptions for a matching source.

### Logarithm semantics corrected while qualifying the rules

Regelsuche's numeric validation gives `log` base 10 semantics and `ln` natural
logarithm semantics. The old calculus rules `exp(log(x)) -> x` and
`log(exp(x)) -> x` were therefore mathematically invalid: `exp` is inverse to
`ln`, not to `log10`. This slice removes those two rules from the calculus
domain instead of promoting them into SAFE. The valid `exp(ln(x))` and
`ln(exp(x))` rules remain, and the coverage test locks that distinction.

## Intentionally excluded algorithmic rules

Exclusion is preferable to an approximate schema that could authorize the
wrong domain.

`rational_cancel_common_factor` remains outside schema-directed preparation.
Its executor accepts two structurally different cancellation orientations,
while the current principal contract contains one source pattern. Choosing one
orientation would be incomplete; guessing a broader pattern would violate the
explicit-schema boundary.

`polynomial_collect_like_terms` and `polynomial_combine_like_terms` also remain
outside. Their applicability depends on whole-sum/numeric-shape semantics not
expressible honestly by the current `PatternExpr` contract. Their direct
executor behavior is unchanged.

The same rule applies to any remaining algorithmic implementation: if its
complete domain and guard semantics are not representable and reviewed, it is
not a normal safe-preparation principal.

## Deterministic near-match ordering

Preparation uses only source-side structural information. The versioned
characterization is `regelsuche.preparation-near-match-ranking/v1`.

For one applicability analysis, lower rank is better in this lexicographic
order:

1. complete match before non-match;
2. more matched pattern nodes;
3. more retained bindings;
4. fewer residual obligations;
5. lower weighted residual bound.

| Residual kind | Lower-bound units |
| --- | ---: |
| `LITERAL_MISMATCH` | 1 |
| `BINDING_CONFLICT` | 2 |
| `SHAPE_MISMATCH` | 3 |
| `FUNCTION_SHAPE_MISMATCH` | 4 |

These are deterministic structural ranking units, not measured CPU time and not
a proof that exactly that many primitive rewrites suffice. Search-side
tie-breakers such as AST growth, primitive path work, structural fingerprint,
rule ID and application key remain separate.

The shared multi-principal traversal uses the same observable progress
components over unresolved principals: number of newly matched principals, best
matched-node count, best binding count, minimum residual count and minimum
residual lower bound before deterministic search-side tie-breakers. Principal
identities are never merged because their ranks happen to be equal.

## Safety boundary

An applicability schema is a preparation and guard-binding contract, not a
mathematical proof edge. A positive prepared candidate still requires:

- allowed equivalence-preserving preparation steps;
- satisfied required assumptions;
- concrete replay of the retained principal executor;
- complete primitive/theory lineage and work accounting;
- versioned evidence tied to repository revision and inventory.

Technical failures remain fail-closed. The matched-work qualification merged in
#967 rejects a technical failure on either `DIRECT_V1` or
`SAFE_PREPARATION_V2`; a broken baseline cannot count as a SAFE capability gain.

## Verification

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.transform.RewriteApplicabilityCatalogTest \
  --tests de.regelsuche.transform.RewriteApplicabilityCatalogGuardBoundaryTest

./gradlew :regelsuche-search:test \
  --tests de.regelsuche.search.reachability.PreparationNearMatchRankingTest
```

Full product qualification still requires the repository-wide CI authorities.

## Related work

- [Safe rule preparation coordinator](safe-rule-preparation-coordinator.md)
- [Rule-directed preparation planning](rule-directed-preparation-planning.md)
- [Learned pattern-rule authorization](learned-pattern-rule-authorization.md)
- [Architecture](architecture.md)
