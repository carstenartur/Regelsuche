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
  -> typed applicability-guard evaluation
  -> bounded pattern-targeted preparation
  -> concrete principal replay
  -> independent bridge verification
```

A desired result expression, benchmark answer, family label or post-hoc score
is never supplied. The principal's applicability schema is the only local
search objective used by the fallback.

## Direct replay is authoritative

The concrete `RewriteRule` executor is always tried against the original AST
before the applicability pattern is used for preparation. This distinction is
important for algorithmic rules: an explicit preparation schema can be
conservative, stale or narrower than the implementation without suppressing a
legal direct application.

The schema still controls binding-dependent guards. If a direct algorithmic
application requires a guard but the declared pattern cannot provide the
bindings needed to instantiate that guard, the coordinator fails closed with an
unsupported outcome. An unguarded direct application is not blocked merely
because the fallback schema does not match.

## Explicit schemas for algorithmic rules

A declarative `PatternRewriteRule` already exposes its source pattern. An
algorithmic Java rule previously had no equivalent preparation boundary.
`RewriteApplicabilitySchema` now keeps five things separate:

```text
schema identity
applicability pattern
recognition profile
typed required-assumption templates
concrete RewriteRule executor
```

The schema deliberately contains no rewrite target. It may guide partial
matching and residual analysis, but it cannot emit a result by itself. A
positive candidate is accepted only when the concrete executor matches and
applies to the exact retained terminal AST. Schema ID, pattern, recognition
profile, required assumptions, executor identity and repository revision are
content-bound.

The existing bridge implementation still requires a `PatternRewriteRule`.
Therefore the coordinator constructs a private adapter for the duration of one
coordinator instance. That adapter delegates every concrete match, application
and assumption to the real executor and is never returned, registered as a
knowledge-pack rule or exposed to the e-graph. In particular, no invented
declarative target can leak into another execution engine.

This avoids both unsafe inference and duplicate implementations:

- no schema is guessed from a class name, rule ID, example or benchmark;
- no declarative target substitutes for an algorithmic implementation;
- concrete direct behavior remains independent of fallback-schema quality;
- a stale or overly broad schema cannot authorize a prepared result when
  concrete replay disagrees;
- the schema and the executor receive distinct, explicit identities.

## Typed guards and assumptions

A required side condition is a precondition, not a result invented by the
coordinator. Every required condition is instantiated from a complete pattern
binding and checked against the assumptions available at the candidate state.
The available set consists of the input assumptions plus assumptions introduced
by independently replayed preparation steps.

For declarative pattern rules the current v1 adapter infers non-zero templates
for symbolic denominator factors visible in the source or target pattern.
Algorithmic rules can declare `RequiredAssumptionTemplate` instances explicitly.
The supported v1 kinds include non-zero, positive, non-negative, integer,
natural, invertible, real and rational conditions.

Guard evaluation is fail-closed:

- a satisfied condition permits concrete replay;
- a missing or unknown condition produces `UNSUPPORTED` rather than a result;
- unavailable bindings produce `UNSUPPORTED`;
- malformed guard evidence produces a technical failure;
- required guards, their instantiated expressions and the assumption signature
  participate in retained identities and recomputation.

A risk label such as `low` never substitutes for mathematical conditions.

## Safe v1 eligibility

The coordinator rejects:

- an empty, duplicate or inconsistently identified schema inventory;
- principal or preparation rules that do not declare equivalence preservation;
- principal rules whose review status is not registration-eligible;
- external principal rules above risk level `low`;
- invalid repository revisions or bridge budgets.

The low-risk boundary is deliberately conservative, but it is not a substitute
for mathematical domain evidence. Logarithm, root, power, inequality and
operator rules with incomplete typed guards remain outside this safe profile
even if a syntactic pattern exists.

## Retained outcome

Every principal receives one deterministic outcome containing:

- rule ID and applicability-schema fingerprint;
- direct, prepared, complete-no-bridge, inconclusive, unsupported or failure
  status;
- concrete candidate when positive;
- cumulative assumptions and guard outcome;
- complete primitive rule lineage;
- initial and terminal match analysis;
- bounded search work and reached limits;
- bridge certificate identity for prepared candidates;
- concrete replay verification status.

The complete evaluation additionally retains principal-schema and preparation
inventory fingerprints plus aggregate work. `verify(...)` recomputes the whole
evaluation and every prepared bridge under the same frozen configuration.

## Learned rules and rewrite programs

The preparation mechanism is not limited to hand-written rules. A learned rule
with a frozen source pattern, replacement, recognition policy, typed guards and
concrete executor can use the same coordinator after it passes the ordinary
validation, provenance and promotion gates.

Raw evolution output is deliberately not promoted merely because it has a
pattern. `EvolutionGenomeCompiler.CompiledGenomeRule` currently declares
`isEquivalencePreservingByConstruction() == false`; the safe coordinator
therefore rejects it. A future promotion contract must bind at least the learned
artifact, TRAIN/VALIDATION/FINAL-TEST separation, equivalence or proof evidence,
required assumptions, counterexamples, risk review and repository revision
before exposing a learned rule as a safe principal.

A learned `RewriteProgram` is a different case. A program may contain choices,
sequences, repetition, requirements and prioritization and therefore has no
single left-hand pattern by construction. It needs a program-level
applicability and replay contract rather than pretending to be one rewrite
rule. That later contract must preserve direct execution, bounded preparation,
primitive lineage and all program guards.

For scientific evaluation, learned principals should be characterized under at
least two inventories:

```text
SHARED_PREPARERS
NO_SELF_LINEAGE_PREPARATION
```

The second profile excludes the learned principal's own primitive lineage from
preparation so that reuse of generally available preparers is not confused with
partial self-execution.

## Current implementation boundary

This revision centralizes eligibility, stage order, deterministic rule order,
typed guards, replay, evidence and reporting. Internally it still delegates one
bounded bridge session per principal. A future shared multi-principal frontier
may remove repeated preparation work, but only as an optimization that
preserves the same candidate, assumption, primitive-lineage and certificate
outcomes.

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

The polynomial fixture hides the powers themselves:

```text
((x^2 * a) / a) - ((y^2 * b) / b)
  -> x^2 - ((y^2 * b) / b)
  -> x^2 - y^2
  -> (x - y) * (x + y)
```

Using `((x*a)/a)^2 - ((y*b)/b)^2` would not be an amplification case because
the unchanged difference-of-squares rule already matches those two arbitrary
bases directly.

The rational rows explicitly declare the denominator conditions needed by the
telescoping identity. Direct and prepared unit-step cases retain `n != 0` and
`n + 1 != 0`; cancellation preparation adds `a != 0` and `b != 0` where
applicable. The schema also retains the inferred denominator templates so the
same principal fails closed when those input conditions are absent.

Generate the deterministic JSON and Markdown evidence with:

```bash
./gradlew :regelsuche-experiments:symPyRuleAmplification
```

The experiment measures amplification of declared applicability. It does not
claim that Regelsuche is generally stronger or faster than SymPy, that the
three rules are complete for their domains, or that preparation is always
beneficial.
