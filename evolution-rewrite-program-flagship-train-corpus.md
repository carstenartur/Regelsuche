# Flagship rewrite-program TRAIN corpus

Status: open TRAIN-only corpus for #533 and #521

The concrete TRAIN suite is assembled by
`FlagshipRewriteProgramTrainCorpus`. It is intentionally public: mutation,
fitness calculation, debugging and independent review may inspect every TRAIN
case.

The suite currently freezes eight assumption-aware rational and polynomial
rewrite tasks covering:

- direct and affine common-factor cancellation;
- equal-denominator addition and cross-denominator subtraction;
- nested division with a shared denominator;
- normalization before cancellation;
- a difference-of-squares factor bridge;
- scaled linear collection.

Every case is checked through
`RationalFunctionNormalFormEquivalencePortAdapter`. The build requires the exact
status `CONFIRMED`, matching normal forms, no missing assumptions and no
unsupported assumptions.

The test suite also removes the cancelled nonzero factor from direct and affine
cancellation controls. Those controls must return `MISSING_ASSUMPTION`; a
system that silently treats cancellation as globally valid cannot pass.

## Frozen search surface

The suite uses the already-declared exact rational evaluator profile and freezes:

- maximum depth: 6;
- maximum visited expressions: 512;
- maximum candidates per state: 40;
- maximum expanding steps: 4;
- beam width: 8;
- primitive-step limit: 6;
- total primitive-work budget: 20,000 units.

These are mechanical TRAIN limits, not statements that every task must consume
the complete budget.

## Information boundary

This file and the corresponding Java class contain TRAIN data only. They do not
contain or derive VALIDATION or FINAL TEST expressions, targets, assumptions,
difficulty labels or expected terminal classes. Held-out material remains behind
the separate commitment and reveal contracts.

## Execution boundary

Adding and verifying this corpus does not start an evolutionary population,
select a candidate, reveal a held-out case or consume FINAL TEST. The next
permitted step is to bind this reviewed suite hash into a `FROZEN_NOT_RUN`
receipt before any TRAIN population execution.
