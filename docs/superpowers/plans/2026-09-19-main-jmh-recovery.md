# Main JMH recovery implementation plan

> **For agentic workers:** Use superpowers:executing-plans. Keep the repair separate from mathematical learning features.

**Goal:** Restore reliable main verification by adopting the measured six-warmup latency protocol from #981 without changing any performance ceiling.

**Architecture:** Keep the frozen threshold/decision policies and verifiers executable. Add separately named current policy instances using the existing schemas and v3 verifier, and bind their exact bytes. Read the execution settings from the current policy in Gradle. Historical charts retain their original two-warmup contract; allocation and SymPy protocols do not change.

**Tech stack:** Java 25, Gradle, JMH 1.36, Python verification.

**Spec:** Issue #981 and `docs/jmh-precision-study-v2-outcome.md`; user instruction to repair main before the learning roadmap.

## Global constraints

Do not increase any `maximumAllowedScore`, multiplier or legacy publication ceiling. Do not change the old measurement policies, verifiers, study inputs or retained historical data. INCONCLUSIVE remains a nonzero exit. Do not rerun a measurement until it passes: one diagnostic rerun of main is retained separately, and adoption is based on the already completed seven-protocol study.

## Review focus

Old two-warmup data must fail the new contract. Six-warmup synthetic data must pass only at the unchanged ceilings. Wrong units and changed iteration duration must fail. The current policy must reach both publication and regression checks. Historical history/acceptance collection must remain complete and distinct.

## Task 1: test and adopt the measured protocol

Files: `scripts/test-jmh-more-warmup-policy.py`; three `config/quality/jmh-*-more-warmup-v1.json` policies; `app/build.gradle`; `gradle/ci-verification.gradle`; `gradle/quality-gates.gradle`; `scripts/collect-quality-acceptance-evidence.py`; `scripts/quality_acceptance_structure.py`; `docs/jmh-production-more-warmup.md`.

- [x] Write migration controls and run `python3 -B scripts/test-jmh-more-warmup-policy.py`; observe failure on missing current policy.
- [x] Copy the existing policy data to separately named instances, change only the accepted warmup count to six, retain the old reference measurements explicitly, and recompute the decision policy's threshold blob binding.
- [x] Wire Gradle execution and both verifiers to those current instances; retain all six policy files in quality evidence and reuse their existing structure definitions.
- [ ] Run the new tests, historical v2/v3 verifier controls, v1/v2 precision harness controls and quality acceptance tests.
- [ ] Publish the isolated fix PR, inspect current-head CI and review, and merge only after successful verification. Check the resulting main run before mathematical feature integration.

## Ruling

Choose `more-warmup` (6/3/1 at 1s/1s), not `more-measurements`: the completed study found zero LOW_PRECISION rows for both, while more-warmup had lower median CI cost (1.749x rather than 1.936x) and lower p90 relative error. This is an explicit engineering choice, not a new study outcome or an automatic winner. The old scores remain historical ceiling references, not measurements under the new protocol.

## Execution evidence

The initial 11 migration tests failed on the missing new policy (RED). After
implementation and correcting the synthetic fixture to include the required
JMH batch-size fields, all 11 pass. Historical v2 controls (2 positive / 6
negative), v3 controls (4 passes / 1 inconclusive / 12 rejections), 22 quality
collection tests, 4 v1 preregistration tests, 7 v2 policy/metric tests and
13 JAR identity tests pass locally. The complete v1 harness exceeded local
invocation limits; it is not claimed to pass. Local Gradle could not download
its distribution because network name resolution is unavailable. Java 25
compilation, real measurements and full CI remain remote acceptance work.
