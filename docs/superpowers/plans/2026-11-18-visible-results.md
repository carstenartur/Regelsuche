# Visible Results Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show current Regelsuche capabilities through usable result views and verified browser captures.

**Architecture:** Extend existing Workbench flows; keep mathematical computation and evidence authoritative. Separate curated gallery material from regenerated algebra evidence and record screenshots only after semantic browser assertions.

**Tech Stack:** Java 25, existing Maven/Gradle modules, plain HTML/CSS/JavaScript, JUnit and Playwright.

**Spec:** `docs/superpowers/specs/2026-11-18-visible-results-design.md`

## Global Constraints

- Keep existing type-safe Java APIs and mathematical results unchanged.
- Keep README status badges at the top; no new dependencies or workflows.
- Distinguish projected pair counts, sample evaluation, explored states and measured logical work.
- Preserve historical evidence and its hashes; use shared-core-v3 for current walkthrough links.
- Screenshots come from a running application, never from mocked API responses or painted result images.
- Do not change the unrelated open Maven CI PR.

## Review Focus

- Changed or rejected input must hide previous success, including after mode changes.
- Empty sample data must not be described as an executed 100,000-row evaluation.
- Keyboard focus and output must remain usable at 390px without horizontal page overflow.
- Normal test runs must not mutate committed screenshots or manifests.
- Generator execution must preserve curated gallery content and valid evidence links.

## Task 1: Pair-reduction result flow

**Files:** `app/src/main/resources/web/pair-reductions.{html,css,js}`; new `app/src/e2eTest/java/de/regelsuche/e2e/PairReductionBrowserFlowTest.java`.

**Interfaces:** Existing `/api/pair-reductions` JSON contract unchanged. Retain existing input/result element IDs. New browser flow is also the source of `pair-reduction-result.png` and `pair-reduction-mobile.png`; root coordinates shared screenshot recording.

- [x] Add behavioral browser checks for verified default result (4,999,950,000 pairs, 100,000 rows, sample 54), meaningful focused result, input invalidation and unsupported input; run the failing checks.
- [x] Improve result hierarchy, explain the pair/row comparison, make derivation inspectable, and retain exact sample provenance. Support existing weighted/ordered/window presets.
- [x] Verify desktop and 390px keyboard/results behavior against the real server.
- [x] Report changed files and test evidence; root handles shared commits.

## Task 2: Gallery generation and public documentation

**Files:** `README.md`, `docs/demo-gallery.md`, `docs/user-workflows.md`, `docs/web-ui-user-guide.md`, `docs/testing.md`, `app/src/main/java/de/regelsuche/docs/DocsDiscoveryGalleryGenerator.java`, relevant tests under `app/src/test/java/de/regelsuche/{docs,demo}`.

**Interfaces:** Captures will be `docs/assets/screenshots/pair-reduction-result.png`, `pair-reduction-mobile.png`, `learned-rule-comparison.png`, `candidate-replay.png`. Root supplies actual capture metadata. Do not manufacture images or dates.

- [x] Add a generator regression exercising preservation of curated content; observe failure before correcting generation.
- [x] Limit generation to owned evidence content (a marked block or a separate generated page), preserve the public evidence contract and update current references to shared-core-v3.
- [x] Rewrite the gallery around input/result/proof/work with new capture links, a concise research-results section and links to detailed algebra evidence.
- [x] Update README and user instructions to match actual UI labels and screenshot claims; retain useful evidence links and badges.
- [x] Run generator/evidence/link checks; report source changes and needed capture integration.

## Task 3: Entry flow, honest macro display and verified capture integration

**Files:** `app/src/main/resources/web/{index.html,app.js,style.css}`; affected landing and macro browser tests; `PublicDiscoveryComparisonBrowserTest.java`, `NativeReplaySelectionBrowserTest.java`; shared test-only recording helper and screenshot artifacts.

**Interfaces:** Existing public comparison serves retained evidence and existing native replay serves executed search states. Keep stable link IDs and current routes.

- [x] Make learning entry reach useful existing comparison content; label recorded runs clearly. Prevent a macro run with no reuse from displaying an unconditional success presentation.
- [x] Add semantic assertions and opt-in capture integration for current comparison and native replay. Record input, route/scenario, source revision and PNG SHA-256 with captures.
- [x] Execute the targeted real browser flows; visually inspect desktop and narrow screenshots and correct actual layout defects.
- [x] Run focused app/generator checks, review the complete diff and publish one focused PR.
- [ ] Follow the complete repository CI, addressing failures in scope.

## Task 4: Historical computation and the failed visual check (2026-11-19)

The user's review rejected the `(x + 0) * (x + 0)` replay as a capability
showcase. Keep it as an occurrence-selection regression control, and lead the
gallery with the existing source-only Sophie-Germain solver instead.

- [x] Add a real HTTP regression and observe the missing endpoint fail. Run
  the existing typed Java solver on `x^4 + 4*y^4`, empty assumptions and the
  general `FACTORIZATION` goal; provide no target expression or witness.
- [x] Retain the actual five verified witness edges in the standard graph and
  replay, including source-bound receipts, budget and measured logical work.
- [x] Add the Workbench result, all intermediate expressions, verification
  details and direct step selection. Check against the real server on desktop
  and at 390px, with a different demo path already present.
- [x] Identify the red CI's one-pixel matrix-capture shift caused by the longer
  entry text. Update only that baseline from the actual pinned CI artifact
  and its content hash; preserve the comparison thresholds. Replay all four
  pinned captures and check the baseline policy.
- [x] Rewrite the README and gallery around the historical result. Review all
  ten open issues against their remaining acceptance criteria; this bounded
  demo does not close the larger research and product requirements.
- [ ] Record the two historical documentation captures from committed source,
  verify their metadata and run the documentation checks.
- [ ] Integrate current main, publish the updated PR and follow its complete CI.
