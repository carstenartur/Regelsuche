# Evaluator-backed discovery challenge portfolio

Issue #390 defines the research-design input for the candidate-independent benchmark in #383. It does not report successful discovery campaigns. It selects only challenge classes with a bounded candidate representation, an independent evaluator or falsifier, a preregisterable split unit, information-parity baselines, and explicit null-result semantics.

## Claim boundary

`EVALUATOR_BACKED` means that a frozen candidate can be checked under a declared finite or symbolic domain. It does not imply:

- external mathematical novelty;
- mathematical importance;
- universal discovery performance;
- asymptotic optimality;
- expert-rated interestingness;
- promotion or Public Evidence eligibility.

External literature and database search is permitted only after candidate formation is frozen.

## Uniform feasibility template

Every assessed class records:

1. mathematical object and candidate representation;
2. information visible and prohibited during formation;
3. independent evaluator and its exact evidence strength;
4. proof or certificate route;
5. split unit and leakage controls;
6. information-parity baselines;
7. multiple-solution and null-result policies;
8. bounded campaign, state, candidate, counterexample, and proof budgets;
9. external comparison sources;
10. a development-only pilot requirement;
11. the selection or deferral rationale.

A missing evaluator, split unit, baseline plan, or bounded resource policy prevents selection.

## Selected classes

### Assumption-sensitive rational rewrites

Candidates are directed rational AST patterns with normalized nonzero assumptions. The evaluator cross-multiplies under those assumptions, compares exact polynomial normal forms, and searches bounded rational counterexamples. Undeclared denominator assumptions and unsupported functions fail closed.

This class extends beyond coefficient collection while retaining a cheap independent symbolic check and explicit side-condition evidence.

### Finite-difference and recurrence hypotheses

Candidates are finite-difference polynomials or bounded recurrences learned from an observed prefix. Held-out continuation and recurrence consistency provide finite-data validation. The contract explicitly forbids interpreting a finite fit as a unique infinite sequence or external novelty.

This class provides a second mathematical object type and direct compatibility with the generic sequence domain.

### Reusable symbolic macros

Candidates are bounded compositions of semantics-preserving primitive transformations. Every step remains replayable. Utility is measured by paired baseline and macro-enabled search on frozen held-out families under identical inputs, targets, inventory, strategy, and budgets.

This class measures reusable systems knowledge. Utility remains separate from truth, novelty, and importance.

## Deferred classes

### Sign-sensitive inequality rewrites

The class has a credible SMT and rational-counterexample evaluator, but it remains deferred until a production domain adapter proves that sign assumptions and comparison-direction changes cannot be weakened or omitted.

### Small combinatorial constructions

The class offers exact finite validity and objective evaluation, but it remains deferred until Regelsuche has a generic finite-construction domain, canonical isomorphism-aware identity, and bounded evaluator. Portfolio inclusion must not be selected merely because a development pilot happened to find a positive result.

## Generated artifacts

The deterministic generator writes:

- `challenge-landscape.json`;
- `challenge-feasibility-report.json`;
- `challenge-split-policy.json`;
- `challenge-baseline-plan.json`;
- `challenge-run-budget.json`;
- `challenge-portfolio.json`;
- `challenge-portfolio-summary.md`.

`challenge-portfolio.json` binds the content hashes of every supporting artifact and names #383 as the consumption target.

## Reproduction

```bash
python3 scripts/generate-discovery-challenge-portfolio.py \
  --source research/challenges/challenge-plan-source.json \
  --output research/challenges/generated
```

The dedicated workflow validates the source and generated schemas, executes two clean generations byte-for-byte, independently recomputes every content hash and cross-artifact root, and rejects target leakage, missing evaluators, elementary selected classes, missing baselines, unbounded budgets, or any positive external-novelty status.

## Remaining work before #390 closes

This slice freezes the portfolio design. The selected challenge pilots must still be executed as development-only evidence, any required domain/evaluator integration must be completed, and #383 must consume the final immutable portfolio identity. A pilot correction creates a new portfolio revision; it does not overwrite this design artifact.
