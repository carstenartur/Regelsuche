# Native replay selection — implementation plan

> Execution: use the executing-plans and test-driven-development skills inline;
> root owns the exclusive Gradle/browser runs and independent review.

**Goal:** Select and deep-link one retained native generation, its exact source
occurrence and path edge while keeping the reviewed end candidate selected.

**Architecture:** Extend the existing immutable workspace selection and the
single dossier correlation adapter. Render only references validated against
the already loaded native artifact. No renderer-owned replay cursor, backend
endpoint, new artifact identity, live radar lookup or proof claim is added.

**Spec:** Approved source audit for issue #669 on commit
`42f5050a28d07fe81bef6fe897b7694699d8f79f`; implementation order step 3.
Existing history, duplicate/compare, dossier and rule-authoring flows remain.

**Stack:** Existing native JavaScript controllers, Java Playwright 1.60.0,
pinned Chromium 148 and the checkout-owned `:app:e2eTest` source set. The
current Maven contract does not contain browser tests; no host Node/npm is added.

## 1. Actual browser RED

- [x] Add `app/src/e2eTest/java/de/regelsuche/e2e/NativeReplaySelectionBrowserTest.java`.
  Execute real bounded native runs and serve their persisted workspaces/artifacts
  through `WebWorkbenchServer`. Assert that `generation=0` is retained, an earlier
  path generation is selected with the final candidate unchanged, and conflicting
  edge/generation references fail visibly. Use existing methods for a behavioral
  RED rather than a missing-function or compilation failure.
- [x] Root runs the zero-generation, earlier-step and foreign-binding methods
  through `:app:e2eTest` with `-Pregelsuche.skipPlaywrightInstall=true --no-daemon`.
  Actual RED: five assertion failures, zero errors/skips; Playwright 1.60.0 and
  Chromium 148.0.7778.96. Log: `build/issue669-controls/browser-red.log`;
  retained XML: `target/native-replay-selection-review/red-NativeReplaySelectionBrowserTest.xml`.

## 2. One additive selection and renderer

- [x] In `candidate-dossier-state.js`, extend
  `selection(artifact, candidateId, edge = '', generation = '')`.
  Require the generation in `candidate.generationSequences`; derive its unique
  transition from the exact retained prefix state and generation reference.
  Reject conflicting explicit edge/generation pairs. With no generation, preserve
  the existing incoming-edge-only contract. Unsupported dossier schemas reject
  generation selection. Never match equal expression strings or parse application keys.
- [x] In `run-workspace-state.js`, preserve an optional canonical nonnegative
  generation string in immutable open/select snapshots; `0` is valid. Candidate
  changes clear old step/edge references; role changes preserve them.
- [x] In `run-workspace.js`, round-trip optional `generation` in fragments and
  forward one validated selection callback. Existing links and all async request,
  Run-ID, ETag and duplication-snapshot checks retain their behavior.
- [x] In `candidate-dossier.js`, add step buttons, previous/next and exact graph
  navigation to the current native replay sections. Selected candidate and context
  edge remain distinct. The selected step determines its occurrence panel and
  focus; rendering reads the immutable selection, never a second local index.
- [x] Add bounded focus/current-step styling in `run-workspace.css` and document
  the additive link contract in `docs/native-target-free-workspace.md`.

## 3. Browser GREEN and retained evidence

- First complete browser run: 23/24 passed; the occurrence fixture incorrectly
  required two visited root successors. A direct Java probe of the same retained
  native execution showed path `[6, 21]`, rewriting the right `[1]` and then left
  `[0]` occurrence of `x + 0`. The control now selects those actual path steps;
  its search budget remains unchanged. The failed run is retained separately.
  Screenshot inspection also prompted stacked labels in the selected step table
  at narrow widths and screenshots aligned with the panel heading.
- [x] Complete real multi-step/repeated-occurrence, zero, old-link, foreign-reference,
  keyboard/reload and 390-pixel flows. Delay real HTTP dossier/duplication responses
  to prove that newer run/step/role selections and focus survive late success/error.
- [x] Root runs the new class with `NativeTargetFreeWorkspaceBrowserTest`,
  `CandidateDossierBrowserTest` and `RetainedRunWorkbenchBrowserTest` through the
  same pinned browser source set. Keep complete results and actual screenshots
  under `build/reports/native-replay-selection` without updating frozen baselines.
- [x] Review screenshots, verify untouched manifest/artifact bytes, retain source,
  test/log/screenshot hashes and exact verification limits in a local receipt, then
  commit this bounded implementation for root's independent review/integration.

Final focused result: all 24 cases in the four browser classes pass, with zero
failures/errors/skips; nine cases belong to the new class. Root's actual run used
Playwright 1.60.0 / Chromium 148.0.7778.96 and completed in 36 seconds.
`build/issue669-controls/browser-green-corrected-fixture.log` and the JUnit XML
retain that result. The five served production resources match their sources
byte for byte. Desktop and 390-pixel screenshots were inspected; original
retained artifact bytes are checked after every new browser case. The local
`target/native-replay-selection-review/verification-receipt.json` records the
exact commit, source/resource, log, XML and screenshot hashes. This is a focused
browser verification; current integration full CI and independent review remain
root-owned.
