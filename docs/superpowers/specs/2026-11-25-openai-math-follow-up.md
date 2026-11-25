# OpenAI Math follow-up: bounded, reusable transfers

Approved task: Carsten asked on 25 November 2026 to examine all interesting approaches from the preceding research assessment and adopt useful parts.

## Goal
Use openai/math@3344da59991ed94f9ba711217b29c1d760badfa6 as a source of precisely scoped methods. Preserve independently checkable correctness, source binding, honest resource accounting, and negative findings. No claim that a headline asymptotic theorem is implemented or that the universal Greedy deadline is proved.

## Architecture
General mathematics belongs in regelsuche-math-algorithms and existing core exact types; regelsuche-optimization-sdk is a thin facade. Primachsen-specific source/chronology modeling belongs in carstenartur/primachsenraum. Extend existing functions when contracts permit; do not duplicate scalar or polynomial representations. Preserve published APIs and existing defaults.

## Binding constraints
- Java 25 and Maven 3.9.9+; no new external dependencies.
- Reuse ExactRational, CertificateWork, IterationLimits, OptimizationBudget and CancellationToken.
- Budget exhaustion, timeout, cancellation and unsupported domains never count as a proof or zero solutions.
- Source equations/expressions and assumptions must be bound to each proof; verification cannot trust producer statuses.
- Mathematical implementations must be documented in Regelsuche, with strategy-index hypothesis, implementation, evidence and conclusion.
- No task-specific answer lookup or benchmark oracle. Test inputs used for assessment are separate from any training inputs.
- Include construction, conversion, verification and runtime costs in the claims actually made. Do not equate work units with elapsed time.
- Tests include negative controls, forged evidence, rational/field boundaries and resource limits. Compiler warnings in changed tests are treated as errors.
- No external theorem or new benchmark advantage is assumed. Record source versions and any formalization limits.
- Do not alter unrelated open PRs or main.

## Accepted bounded tasks
1. Exact rational LP output verification and Farkas support reduction.
2. Structured linear-operator residual repair and occupied-workspace restoration, only with explicit finite operator identities and full cost comparison.
3. Common-scalar-center comparison of rational noncommutative formulas, if exact finite reachable-space verification is implementable within existing realization infrastructure.
4. Prime-field polynomial factorization with multiplicities using existing square-free factoring and exact revalidation.
5. Primachsen finite chronology encoding via the existing generic CNF counter, source-semantic tests including the first invalid omission 86 in the p=71 model, and a sunflower/Hall structural audit.
6. Updated transfer audit covering all proposed approaches, including reasons to defer full strongly polynomial LP, black-box generators, asymptotic multiplication, and online-minor/partial-sign/Hirsch transfers.

The user authorized implementation in the current turn; no further design permission is required.
