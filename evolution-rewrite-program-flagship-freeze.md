# Flagship preregistration and freeze boundary

Status: pre-execution contract foundation for #533 and #521

## Purpose

The flagship experiment must not begin by running an evolutionary population.
Its last reversible step is a content-addressed preregistration that binds the
complete mathematical and engineering surface while every result remains
absent.

The freeze layer separates four concerns:

1. concrete TRAIN inputs available to candidate generation;
2. sealed VALIDATION and FINAL TEST commitments unavailable to earlier stages;
3. numerical success and transparent-null-result thresholds;
4. environment-qualified performance measurement, kept distinct from the
   canonical mathematical-work ledger.

No class introduced by this layer evaluates a case, selects a candidate or
reveals a held-out target.

## Held-out commitments

`EvolutionRewriteProgramHeldOutCommitment` binds either VALIDATION or FINAL TEST
before evaluated search. Every entry retains only stable identities and hashes:

- case and family commitment;
- input, target and assumptions;
- exact and alpha signatures;
- difficulty tier and expected terminal class;
- sealed reveal entry.

The complete reveal has its own hash. VALIDATION may be revealed only after the
TRAIN population is complete. FINAL TEST remains one-time and unavailable until
a complete candidate and VALIDATION selection are frozen.

The commitment must match the corresponding surface of
`EvolutionSplitManifest`. Case, input, target, exact, alpha and reveal-entry
identities are unique within the committed split; the split manifest continues
to enforce cross-split disjointness.

## Numerical thresholds

`EvolutionRewriteProgramAcceptanceThresholds` fixes the positive and null-result
routes before TRAIN execution. A positive claim requires multiple improved cases
and structural families, genuine multi-step composition plus decision topology,
zero correctness regressions, zero hidden-assumption regressions and zero
technical failures.

A result may succeed through a newly reached held-out case or through the
explicitly enabled material mechanical-work-reduction route. The latter uses the
same matched primitive and total-work ledger introduced by #528. Elapsed time
cannot replace that ledger.

The frozen FINAL TEST surface must be capable of satisfying the thresholds and
must contain at least three distinct committed structural families. Otherwise a
freeze receipt cannot be created.

## Performance envelope

Optimization is mandatory engineering work, but it is not allowed to alter the
scientific comparison silently.

`EvolutionRewriteProgramPerformancePlan` binds:

- benchmark, implementation and runtime-environment revisions;
- warm-up, measurement, fork and sample policy;
- allocation measurement;
- executor, AST match/rewrite, canonicalization, deduplication, frontier,
  exact-audit and end-to-end layers;
- fixed-work and fixed-time measurements;
- a pinned-environment regression threshold;
- byte-identical canonical evidence and work metrics;
- reference-backend fallback.

The scientific resource authority remains:

```text
canonical primitive and total-work ledger
```

Wall-clock time remains:

```text
environment-qualified engineering diagnostic
```

A faster backend may process more work in an explicitly declared engineering
profile. It may not retroactively change the frozen mechanical budget or earn a
mathematical success claim merely by running on faster hardware.

## FROZEN_NOT_RUN receipt

`EvolutionRewriteProgramFreezeReceipt` binds:

- clean repository commit;
- split manifest and TRAIN suite;
- VALIDATION and FINAL TEST commitments;
- evaluation protocol and population study plan;
- acceptance thresholds;
- primitive inventory, program grammar and mutation catalog;
- baseline and ablation plan;
- performance plan and schema bundle.

Its only valid state is:

```text
status: FROZEN_NOT_RUN
TRAIN: NOT_EVALUATED
VALIDATION: NOT_EVALUATED
FINAL_TEST: NOT_EVALUATED
```

Any substituted input changes the receipt identity or is rejected by
`requireInputs`. Actual corpus values and a real freeze receipt are a later
reviewed slice; this foundation deliberately contains only deterministic test
fixtures.

## Optimization discipline

Every future optimization must answer four questions before production use:

1. Which measured hot path does it address?
2. Does it preserve ordered outputs, assumptions, lineage, trace meaning and
   canonical evidence exactly?
3. Does it improve end-to-end useful search work, not only a synthetic
   microbenchmark?
4. Are compilation, memory, cache and fallback costs bounded and retained?

The current measurements show that rewrite-program topology dispatch is too
small to justify bytecode generation. Prepared AST execution and matcher work
are therefore investigated separately under #530, without changing this freeze
contract.

## Claim boundary

This layer establishes preregistration mechanics only. It does not freeze the
actual flagship corpus, execute TRAIN, expose VALIDATION, consume FINAL TEST,
prove a positive result or establish external mathematical novelty.
