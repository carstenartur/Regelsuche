# Combined-program VALIDATION bridge implementation plan

> **For agentic workers:** Execute inline with superpowers:executing-plans. Do not delegate this task.

**Goal:** Consume a completed combined genome/RewriteProgram TRAIN population through real, one-shot VALIDATION selection and an immutable unqualified downstream handoff.

**Architecture:** Add a versioned plan binding the full terminal candidate population, each primitive-work budget and the committed VALIDATION case surface. A study-scoped file reservation precedes the lazy reveal loader. The current compiler, measured BestFirst search and exact rational-normal-form adapter produce complete paired evidence; existing VALIDATION aggregates and ranking semantics select one complete configuration. A separate handoff retains that selected configuration without advancing downstream gates.

**Tech Stack:** Java 25, existing learning/search/math-algorithms modules, strict Jackson codecs, JUnit 6, offline Maven.

**Spec:** The bounded design in this document implements issue #220's ordinary VALIDATION integration. Existing contracts are described in `docs/evolution-validation-selection.md`, `docs/evolution-rewrite-program-held-out-reveal.md` and `docs/evolution-final-test-once.md`.

## Global constraints

- Work from exact commit `eb904ef0b7831f758f980c85db317c4a8a665a4e` in the isolated `issue220-program-validation` worktree.
- Preserve every v1 canonical algorithm and frozen fixture. Never encode a program hash as a v1 genome hash.
- Use only public synthetic test cases; never load actual heldouts or invoke protected FINAL TEST, #533 freeze, policy tuning or production promotion.
- No Gradle, Maven install, GitHub writes or child agents. Source `runtime/env.sh` before focused offline Maven.
- FINAL TEST, proof, novelty, promotion and public-evidence authority remain `NOT_EVALUATED`.

## Task 1: Bind and execute the complete terminal population

**Files:** Add `EvolutionRewriteProgramValidationPlan`, `EvolutionRewriteProgramValidationRunner`, `EvolutionRewriteProgramValidationSelection`, `FileEvolutionRewriteProgramValidationAttemptStore` and a paired case evaluator in `regelsuche-learning/src/main/java/de/regelsuche/evolution/`. Extend the existing reveal authorization with a combined-population overload. Add `EvolutionRewriteProgramValidationRunnerTest` under the matching test package.

**Interfaces:** `Plan.create(study, manifest, retainedTrainRun, commitment, budgets)` constructs the complete candidate × budget matrix. `Runner.executeOnce(plan, study, manifest, retainedTrainRun, commitment, loader, store)` reserves before calling `loader.load()` and executes the native evaluator. The selection contains ordered paired rows and a complete selected configuration hash.

- [x] Write a synthetic real TRAIN→VALIDATION test with two programs sharing one genome; assert both survive as different configurations and both retain nonzero real work.
- [x] Run focused offline Maven and retain the missing-bridge failure.
- [x] Implement strict plan binding, additive authorization, the measured evaluator and selection. Reuse `EvolutionValidationCandidate` for aggregate and eligibility checks; use the existing ranking fields with the new complete configuration hash as the final tie-break.
- [x] Run the new test plus existing selection, population and evaluator tests.

## Task 2: Enforce reservation and immutable handoff boundaries

**Files:** Extend the new test and add `EvolutionRewriteProgramValidationHandoff`; document the productive flow in `docs/evolution-validation-selection.md`.

**Interfaces:** The store uses a study/split filename independent of program, run or budget. `selection.handoff()` produces a strict immutable artifact carrying the full selected configuration and unadvanced gate states. No method grants promotion or opens FINAL TEST.

- [x] Add controls that observe the durable reservation inside the lazy loader and reject a second attempt after loader failure, including a replacement budget.
- [x] Add roundtrip and tamper controls for program payload, candidate removal, search budget, paired aggregates, selection substitution and premature promotion. Hand-recomputed hashes must not conceal inconsistent nested identities or selection.
- [x] Run the controls, implement missing guards, and retain focused Maven output.
- [x] Update the documentation with exact implemented and remaining boundaries; run the focused suite and inspect the diff before committing.

## Review boundary

The protected study and a separate additive combined-program FINAL TEST suite/reservation adapter remain outside this slice. The existing v1 final executor carries only genome/search identity, so this bridge must not claim it can execute a selected program safely through that interface. Root owns independent review, integration and complete CI.

## Execution evidence

The final focused offline Maven run passed 71 tests across 12 suites, including 11 public bridge
controls. The bridge controls execute real seeded and mutated TRAIN populations, native paired
VALIDATION searches, a native refutation, full work-vector parity, audit/reveal/result-write failure,
strict imports, selected-program tampering and the owner-only POSIX file boundary. Behavioral RED
logs retain failures for incomplete outcome acceptance, rehashed case-family/path substitution and
post-reveal file permissions. Generated learning classes were removed before the final run and
incremental compilation was disabled to ensure the latest evolved-survivor control was included.

Logs are local generated output under `target/issue220-review/`; the final run is
`final-verified-maven.log`. No protected study, actual heldout, Gradle task or GitHub write was
performed. Independent review and full integrated CI remain with the integrating owner.
