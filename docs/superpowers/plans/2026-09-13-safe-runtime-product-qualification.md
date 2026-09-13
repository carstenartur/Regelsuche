# Public SAFE Runtime Qualification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retain and reproduce an honest bounded DIRECT/V4 decision through actual product endpoints.

**Architecture:** A fixed public manifest and preverified input fixture feed the installed application through CLI and HTTP. A separate Python verifier reconstructs every decision from canonical raw artifacts. Checkout-owned Gradle orchestrates normal verification and clean-checkout/container reproduction.

**Tech Stack:** Java 25, existing Maven/Gradle authority fixtures, Python 3.11+, jsonschema 4.25.1, installed App CLI/HTTP, digest-pinned Docker.

**Spec:** `docs/superpowers/specs/2026-09-13-safe-runtime-product-qualification-design.md`

## Global Constraints

- V2 authorities and historical evidence remain unchanged; V4 identities stay explicit.
- No protected FINAL TEST, flagship freeze, product-clock override or default switch.
- The runner loads verified input authorities; it never constructs authorization.
- Same full visible inventory and paired source/assumption/selection/work inputs.
- Technical failures, unsupported baselines and inconclusive DIRECT work cannot create gain.
- Typed relation/downstream replay and learned leaf/program authority remain intact.
- Use two Gradle workers at most; coordinate the shared Gradle slot with root.

## Task 1: Premeasurement public input authority

**Files:** `regelsuche-learning/src/test/java/de/regelsuche/evolution/LearnedRewriteProgramAuthorizationEvidenceFixtureWriter.java`; new `LearnedRuntimeProductFixtureWriter.java` and test; `config/qualification/safe-runtime-public-v1.json`; `reproduction/safe-runtime/QualificationPlugin.java`.

**Interfaces:** `LearnedRuntimeProductFixtureWriter.main([output])` exports `valid/`, `expired/`, `runtime-valid.json`, `runtime-expired.json` and `inputs.json`. Old writer main keeps its original bytes. The manifest pins public expectations before any measured run.

- [x] Add a test that invokes the new exporter, retains legacy output and replays both valid and expired receipt roots with the existing services; observe red before implementing the exporter.
- [x] Extract only an expiry-parameterized internal export method from the existing program fixture; keep its original main/default identical.
- [x] Add the fixed-window wrapper, full manifests and static contract-fixture declaration; independently validate both leaf roots and the program.
- [x] Commit the public corpus, exact budgets and gain/default criteria before running comparisons.
- [x] Run focused fixture and historical authorization tests; commit the verified input authority.

## Task 2: Actual product runs and independent decision

**Files:** new `scripts/safe_runtime_qualification/{protocol,run,verify,test_protocol,test_runtime}.py`; new `docs/schemas/regelsuche-safe-runtime-product-qualification-v1.schema.json`.

**Interfaces:** `run.py --app-home PATH --inputs PATH --manifest PATH --revision SHA --output PATH` retains `qualification.json` and `artifacts/<case>/<profile>/<surface>-<stage>.json`. `verify.py --root PATH --manifest PATH --schema PATH --app-home PATH --inputs PATH --revision SHA` independently validates and recomputes the report against an external source revision. Pure `protocol.evaluate(manifest, observations)` returns derived expectations, gains, coverage and decision.

- [x] Retain red cases for DIRECT technical/budget failure presented as gain, changed paired budget/assumption/inventory, and forged replay or work evidence.
- [x] Implement strict duplicate-key loading, canonical hashes, fixed catalog membership and derived candidate/work comparisons.
- [x] Implement bounded actual App CLI processes plus one actual serve process, all with identical fixture working directories and manifest property; retain analyze and fresh replay bytes from both surfaces.
- [x] Assert the real expired control fails and artifact/profile/lineage/typed-relation/learned-root mutations are refused by the actual endpoints.
- [x] Run the fixed public corpus without changing criteria; preserve any failure as evidence. Commit runner, verifier and tests after their focused checks.

## Task 3: Reproduction and checkout-owned gates

**Files:** new `scripts/safe_runtime_qualification/reproduce.py`; `gradle/safe-runtime-product-qualification.gradle`; `Dockerfile.safe-runtime-qualification`; Gradle apply/task wiring and focused workflow-contract tests.

**Interfaces:** `reproduce.py --repository-root PATH --revision SHA --output PATH` prepares two clean source checkouts and a pinned container, invokes the same run/verify commands, compares the complete canonical outputs and writes a reproduction receipt. Missing Docker or any divergent identity/byte is failure.

- [x] Add tests that reject mismatched revisions, dirty inputs, differing inventories/implementation identities, missing or extra artifacts and absent container evidence.
- [x] Wire additive public input export, input verification, app distribution, corpus execution and independent report verification tasks.
- [x] Implement independent clean checkout builds, bounded execution and strict complete-output comparison; add the digest-pinned offline container target.
- [x] Add the normal verifier to check and the full reproduction to fullCheck/ciCheck without weakening existing gates.
- [x] Run two real clean local checkouts, focused Gradle/Maven tests and container build/launcher contract checks.
- [ ] Run the actual pinned container and complete integrated gates in CI; local Docker is unavailable and no container success is inferred.

## Task 4: Measured documentation and review

**Files:** README, `docs/architecture.md`, `docs/web-ui-user-guide.md`, `docs/shared-safe-runtime.md`, new public qualification documentation and the existing generated capability-status mechanism.

- [x] Derive documentation from the retained qualification and coverage counts, including the evidence-backed reason for any opt-in decision.
- [x] Keep frozen V2 claims explicitly historical and the public contract-fixture claim boundary visible.
- [ ] Obtain independent review of raw artifact authority, negative/gain logic, expiry handling and reproduction gates; fix confirmed findings with real red/green cases.
- [ ] Commit the verified tree and report exact content identity, tests and outstanding full integration gates to root. Do not publish via API.

## Local evidence checkpoint (2026-09-13)

- Frozen cases: `887283d3e7`; additive public authority: `e61933f3a0df1f9a4596335b3b63e5fda5cffa04`.
- First installed product report: `sha256:466252e9e8ab438b6e96caa5d93749ab6a258373e25d52a474f8d62a782fb354`; 14 cases, 112 observations, 2 gains, 0 expectation/regression failures. Independent actual CLI/HTTP replay and rehashed-forgery rejection passed.
- Focused 26 Python checks and 13 Maven documentation/container-policy contracts passed after the new pinned image was explicitly added to policy.
- Two independent clean checkouts of `e71cb7bc6a51aee64ddad6b8790acd15552ebf46` each executed all 56 required build tasks without build-cache reuse and passed the real corpus and independent replay. All 127 canonical files matched; output-tree hash `sha256:fb54a0342292118fd10eb5cc8ed2ab6d838046d7f0b737c0fff91eec4042f5c4`, report hash `sha256:b44d550b9f2badb60cb6d5720c932ef0c050d5ae3e4034fd7b9c78fa629d0e43`.
- Independent review exposed a correctly rehashed false report revision and aggregate statuses hiding incomplete principal outcomes. The verifier now requires an external revision and rejects every excluded individual outcome, with a narrowly declared missing-guard exception. The real report remains unchanged; the real rehashed-revision and coherent-result attacks are rejected.
- Actual container execution and exact integrated full CI remain pending; no broad research or default qualification follows from this checkpoint.
- The checkout-owned `verifySafeRuntimeProductQualification checkForgedSafeRuntimeProductQualification` run passed on committed `7b5dbc417ba8a05461698471d7d4342ca8b7fc0c` (60 tasks, 2m50s). Its report is `sha256:d51bf6b2500eeda81f245322fc0d1268cea8d0227bed58f1028329a48ea23168`; all declared counts and work totals remain unchanged.
