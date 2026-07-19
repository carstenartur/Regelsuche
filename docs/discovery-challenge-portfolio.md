# Evaluator-backed discovery challenge portfolio

Issue #390 defines the research-design input for the candidate-independent benchmark in #383. It does not report successful evaluated discovery campaigns. It selects only challenge classes with a bounded candidate representation, an independent evaluator or falsifier, a preregisterable split unit, information-parity baselines, and explicit null-result semantics.

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

## Generated design artifacts

The deterministic generator writes:

- `challenge-landscape.json`;
- `challenge-feasibility-report.json`;
- `challenge-split-policy.json`;
- `challenge-baseline-plan.json`;
- `challenge-run-budget.json`;
- `challenge-portfolio.json`;
- `challenge-portfolio-summary.md`.

`challenge-portfolio.json` binds the content hashes of every supporting JSON artifact and names #383 as the consumption target. The Markdown summary is presentation output rather than a separately hash-addressed contract document, but it remains part of the byte-for-byte generated tree and is therefore still covered by reproducibility checks.

Regenerate the design artifacts with:

```bash
python3 scripts/generate-discovery-challenge-portfolio.py \
  --source research/challenges/challenge-plan-source.json \
  --output research/challenges/generated
```

## Development-only pilots

The selected classes are exercised by a checkout-local pilot runner:

```bash
python3 scripts/run-discovery-challenge-development-pilots.py
```

It executes ordinary Gradle/JUnit contracts for:

- finite-difference confirmation, holdout refutation, and incomplete audit budgets;
- fail-closed rational division without a nonzero assumption and lossless translation with the assumption;
- replay-based macro learning, independent equivalence validation, guarded reuse, and assumption-preserving rationalization.

The deterministic receipt is written to:

```text
build/reports/discovery-challenge-pilots/report.json
```

The receipt is explicitly marked `DEVELOPMENT_ONLY_PASSED`, keeps the benchmark campaign at `NOT_STARTED`, and keeps external novelty at `NOT_EVALUATED`. It binds the frozen portfolio content hash but is not benchmark evidence.

## Checkout-local verification

The authoritative verification command is:

```bash
./gradlew verifyDiscoveryChallengePortfolio
```

It is part of root `./gradlew check`. The repository-owned verifier:

1. validates the fail-closed source and artifact schemas with the pinned verification environment;
2. follows all six local `oneOf` references in the combined artifact schema and requires `unevaluatedProperties: false` on every referenced artifact definition;
3. executes two clean generations in isolated temporary directories;
4. compares both complete generated trees byte-for-byte, including `challenge-portfolio-summary.md`;
5. compares the complete generated tree with the committed frozen portfolio;
6. separately requires exactly the six expected JSON contract artifacts and no additional JSON files;
7. independently recomputes every JSON content hash and cross-artifact root;
8. verifies assessed/selected/deferred counts and the exact selected challenge identities;
9. enforces post-formation external search, information-parity baselines and conservative claim statuses;
10. rejects mutated inputs with missing target prohibitions, blank evaluators or non-executable budgets.

The distinction between the six JSON contract artifacts and the complete generated tree is intentional: schema and hash checks apply to the JSON contracts, while byte reproducibility also covers the Markdown summary.

All fixtures, expected values and negative cases live in
`scripts/verify-discovery-challenge-portfolio.py`. The former dedicated GitHub
Actions workflow is therefore unnecessary. Central CI invokes the same Gradle
lifecycle as an ordinary checkout.

The development-pilot workflow remains a thin adapter for the separate
repository pilot script. GitHub does not define pilot cases or assertions.

## Remaining work before #390 closes

The development-pilot receipt must pass on the frozen portfolio. After that, #390 can close as the design-and-pilot issue because #383 already consumes the immutable portfolio identity. Evaluated candidate-independent campaigns, retained campaign failures/null results, aggregate benchmark reporting, and reproduction remain in #383.
