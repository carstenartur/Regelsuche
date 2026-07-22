# Exact rational-function equivalence

## Purpose

`RationalFunctionNormalFormEquivalenceService` provides deterministic,
assumption-aware validation for bounded rational expressions without invoking an
external CAS or prover.

It is intended for benchmark evaluation, counterexample triage and rule-quality
gates where a rational rewrite must be checked together with its declared pole
conditions.

## Supported expression domain

The service accepts expressions built from:

- integer and finite decimal literals accepted by the existing double-based expression parser;
- symbolic variables;
- addition and subtraction;
- multiplication and division;
- explicit non-negative integral powers up to the configured bound.

Functions such as `sin`, `log` or `sqrt`, negative powers and non-integral
powers are outside this evaluator and return `UNSUPPORTED`.

## Evaluation method

Every expression is converted recursively to an exact rational function

```text
N(x_1, ..., x_n) / D(x_1, ..., x_n)
```

using the project `Polynomial` and `Rational` implementations. Equality of
`N_1/D_1` and `N_2/D_2` is decided by comparing the deterministic polynomial
normal forms of

```text
N_1 * D_2
```

and

```text
N_2 * D_1.
```

After parsing, all arithmetic is exact rational/polynomial arithmetic; it is
not a numeric sample test. Decimal source literals are first interpreted by the
existing IEEE-754 `double` parser, so the evaluator does not claim arbitrary-
precision decimal input parsing.

## Assumption audit

The evaluator retains the non-constant denominator factors visible in the
rational AST. Every factor must be covered by an explicit `!=` assumption.

Assumptions are parsed as polynomial differences and normalized to monic form.
Therefore the following conditions are recognized as equivalent:

```text
x != -3
x + 3 != 0
-3 != x
```

Likewise, `x != a` covers the denominator factor `x - a`, and `y != -b`
covers `y + b`.

The evaluator does not infer a missing pole condition from the expression and
does not silently treat an unsupported free-form assumption as sufficient.

## Result statuses

| Status | Meaning |
|---|---|
| `CONFIRMED` | Cross-multiplied normal forms match and every required denominator factor is declared non-zero. |
| `REFUTED` | Domain assumptions are complete, but the cross-multiplied normal forms differ. |
| `MISSING_ASSUMPTION` | The identity may be algebraically correct, but at least one visible denominator factor lacks a non-zero condition. |
| `UNSUPPORTED` | The expression or assumption lies outside the bounded evaluator domain. |

Every result retains:

- left and right cross-multiplied normal forms;
- required and provided non-zero factors;
- missing factors;
- unsupported assumptions;
- a human-readable terminal detail.

## Frozen benchmark coverage

The unit tests cover all six rational identity families frozen for issue #383:

- direct factor cancellation;
- affine factor cancellation;
- difference-of-squares followed by cancellation;
- partial-fraction identity validation;
- nested-division identity validation;
- parameterized difference-of-squares cancellation.

This does not mean the autonomous benchmark adapter has discovered or reached
all six targets. The evaluator validates a proposed result; it does not provide
targets or construct the discovery path.

## Claim boundary

A `CONFIRMED` result is an exact equality result inside the supported rational
function domain under the supplied assumptions. It is not:

- a proof for functions or unsupported expression domains;
- permission to omit assumptions from the candidate or replay;
- evidence that an autonomous search formed the rule;
- external mathematical novelty;
- publication authorization.
