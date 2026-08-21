# Rational common-denominator preparation

Issue #708 places bounded, certificate-carrying preparation between direct
matching and the global search frontier. This slice handles two rational terms
whose denominators differ, so the existing rational-normalization operator
cannot yet combine them.

## Example

For

```text
a / b + c / d
```

the solver constructs the identical denominator `b * d` on both sides:

```text
(a * d) / (b * d) + (c * b) / (b * d)
```

It then replays the existing `hypothesis_rational_normalization` operator to
obtain:

```text
(a * d + c * b) / (b * d)
```

The retained primitive lineage is:

```text
prepare_rational_common_denominator
hypothesis_rational_normalization
```

Subtraction preserves operand order:

```text
a / b - c / d
  -> (a * d) / (b * d) - (c * b) / (b * d)
  -> (a * d - c * b) / (b * d)
```

## Direct path before preparation

At every inspected AST position the engine first distinguishes whether the
principal operator already applies. Equal denominators remain a one-primitive
direct transformation, including inside a larger expression:

```text
sin(a / b + c / b)
  -> sin((a + c) / b)
```

The local adapter replays the concrete existing operator and replaces only that
subtree. It does not label this direct move as preparation, and the root-level
result is deduplicated against the ordinary base engine.

## Fragment and assumptions

The root must be an addition or subtraction with exactly one division on each
side. Numerators and denominators may be arbitrary term ASTs inside the
configured node limits. Explicit zero denominators are rejected. Multiplication
is interpreted in the scalar commutative domain used by the existing rational
operator.

The principal operator retains the side condition:

```text
b * d != 0
```

This condition is sufficient for both denominator extensions and for the final
fraction. It is not silently discharged or split into weaker-looking textual
assumptions. Purely numeric non-zero denominators require no symbolic
assumption.

If the two denominators are already equal under the canonical scalar
representation, the result is `DIRECT_MATCH_AVAILABLE` and no preparation is
invented. An input or constructed-tree limit yields `BUDGET_INCONCLUSIVE`, never
a negative mathematical fact.

## Retained evidence

Each prepared application retains:

- original, prepared, and result ASTs;
- original numerators and denominators as `A/B` and `C/D`;
- both scaled numerators and the common denominator `Q`;
- the residual common-denominator obligation;
- exact assumption and primitive-rule lists;
- balanced input and constructed-node work;
- structure hashes for the original, common denominator, prepared, and result
  ASTs, including function arguments and exact number bits;
- a content-addressed certificate.

Verification rebuilds the complete prepared and result ASTs from the original
fractions, recomputes all work and structure hashes, and checks the certificate.
The engine emits a candidate only when the real rational-normalization operator
replays to the exact expected result and assumptions.

## Opt-in use and claim boundary

```java
TransformationEngine engine =
    new RationalCommonDenominatorPreparationTransformationEngine();
```

The engine composes all earlier preparation paths and direct rational
normalization. Historical engines and benchmark identities remain unchanged.

The claim is limited to bounded cross-multiplication for two scalar rational
terms. It does not establish denominator minimization, polynomial GCD/LCM
computation, cancellation, general rational simplification, global reachability,
or search superiority.
