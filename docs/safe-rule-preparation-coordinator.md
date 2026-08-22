# Safe rule-preparation coordinator

Issue #708 requires the specialized preparation paths and the bounded local
bridge fallback to converge behind one product-facing policy boundary. This
tranche introduces the first coordinator revision:

```text
regelsuche.safe-rule-preparation-coordinator/v1
```

## Purpose

The coordinator receives a finite set of visible principal schemas, one finite
preparation inventory, an exact repository revision and one bounded bridge
budget. For every principal it performs the same staged decision:

```text
concrete direct replay
  -> bounded pattern-targeted preparation
  -> concrete principal replay
  -> independent bridge verification
```

A desired result expression, benchmark answer, family label or post-hoc score
is never supplied. The principal's applicability schema is the only local
search objective.

## Explicit schemas for algorithmic rules

A declarative `PatternRewriteRule` already exposes its source pattern. An
algorithmic Java rule previously had no equivalent preparation boundary.
`RewriteApplicabilitySchema` now keeps four things separate:

```text
schema identity
applicability pattern
recognition profile
concrete RewriteRule executor
```

The schema deliberately contains no rewrite target. It may guide partial
matching and residual analysis, but it cannot emit a result by itself. A
positive candidate is accepted only when the concrete executor matches and
applies to the exact retained terminal AST. Schema ID, pattern, recognition
profile, executor identity and repository revision are content-bound.

The existing bridge implementation still requires a `PatternRewriteRule`.
Therefore the coordinator constructs a private adapter for the duration of one
coordinator instance. That adapter delegates every concrete match, application
and assumption to the real executor and is never returned, registered as a
knowledge-pack rule or exposed to the e-graph. In particular, no invented
declarative target can leak into another execution engine.

This avoids both unsafe inference and duplicate implementations:

- no schema is guessed from a class name, rule ID, example or benchmark;
- no declarative target substitutes for an algorithmic implementation;
- a stale or overly broad schema fails when concrete replay disagrees;
- the schema and the executor receive distinct, explicit identities.

## Safe v1 eligibility

The coordinator rejects:

- an empty, duplicate or inconsistently identified schema inventory;
- principal or preparation rules that do not declare equivalence preservation;
- principal rules whose review status is not registration-eligible;
- external principal rules above risk level `low`;
- invalid repository revisions or bridge budgets.

The low-risk boundary is deliberately conservative, but it is not a substitute
for mathematical domain evidence. A rule whose validity needs side conditions
must receive those conditions through the source assumption context or a typed
guard before a positive result can be qualified. Logarithm, root, power,
inequality and operator rules with incomplete typed guards remain outside this
safe profile even if a syntactic pattern exists.

## Retained outcome

Every principal receives one deterministic outcome containing:

- rule ID and applicability-schema fingerprint;
- direct, prepared, complete-no-bridge, inconclusive or failure status;
- concrete candidate when positive;
- cumulative assumptions;
- complete primitive rule lineage;
- initial match analysis;
- bounded search work and reached limits;
- bridge certificate identity for prepared candidates;
- concrete replay verification status.

The complete evaluation additionally retains principal-schema and preparation
inventory fingerprints plus aggregate work. `verify(...)` recomputes the whole
evaluation and every prepared bridge under the same frozen configuration.

## Current implementation boundary

This revision centralizes eligibility, stage order, deterministic rule order,
replay, evidence and reporting. Internally it still delegates one bounded bridge
session per principal. A future shared multi-principal frontier may remove
repeated preparation work, but only as an optimization that preserves the same
candidate, assumption, primitive-lineage and certificate outcomes.

The earlier specialized exact solvers for polynomial quotient, AC exposure,
common monomials, exact squares and common denominators are not removed by this
tranche. Adapting those solvers to one common registry is the next coordinator
slice.

## Three-family SymPy amplification matrix

The retained experiment is advanced from one Pythagorean rule to three
unchanged low-risk imported SymPy rules:

```text
sympy.trig.pythagorean
sympy.poly.factor.diff_squares
sympy.rational.partial_fraction.telescoping
```

All three share the same preparation inventory containing only
`ast_cancel_division_factor`. Eleven frozen cases cover:

- four direct applications;
- four additional prepared applications;
- three conclusive near misses;
- trigonometric, polynomial and rational rule families;
- one- and two-step preparation;
- retained non-zero assumptions;
- rejection of different-argument, wrong-operator and wrong-step controls.

The rational rows explicitly declare the denominator conditions needed by the
telescoping identity. In particular, direct and prepared unit-step cases retain
`n != 0` and `n + 1 != 0`; cancellation preparation adds `a != 0` and `b != 0`
where applicable. The existing pack risk label alone does not authorize those
conditions.

Generate the deterministic JSON and Markdown evidence with:

```bash
./gradlew :regelsuche-experiments:symPyRuleAmplification
```

The experiment measures amplification of declared applicability. It does not
claim that Regelsuche is generally stronger or faster than SymPy, that the
three rules are complete for their domains, or that preparation is always
beneficial.
