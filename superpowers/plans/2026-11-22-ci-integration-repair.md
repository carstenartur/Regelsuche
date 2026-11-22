# CI integration repair implementation plan

> **For agentic workers:** Use superpowers:executing-plans or superpowers:subagent-driven-development for the assigned task. The user has authorized completing the existing CI, PR and issue work.

**Goal:** Repair the combined-main quality failure, finish the existing Student SDK PR on current main, and record the actual remaining issue work.

**Architecture:** Keep the existing source authorities and limits. Use the original measured reports of an independently qualified, already integrated predecessor with an identical tested Git tree. Preserve ongoing main verification while cancelling obsolete PR runs; publish reports only for the current main revision.

**Tech stack:** Java 25, Maven, Gradle 9.9.1, released AI Knowledge extractor 0.1.10, GitHub Actions.

**Spec:** The current user request and the independently qualified predecessor policy in `docs/ai-knowledge.md`.

## Global constraints

- Keep the context-token allowance at 15000, concept-radius allowance at 3 and all other quality/test policies unchanged.
- No rejected candidate contributes baseline values. Retain exact source, tree, original CI and artifact identities.
- Distinguish successful source-tree CI from cancelled post-merge push CI.
- Keep the existing PR; update and test it against current main before ordinary SHA-bound merge.
- Close issues only when their actual acceptance criteria are met.

## Review focus

- Reused measurements must bind to the exact tree integrated on main, not merely a similar feature branch.
- Missing, altered or duplicate artifact members must reject the provenance audit.
- A new main push must preserve running main verification. Superseded pending revisions may coalesce into the latest main run; a new PR revision should cancel obsolete PR work.
- Failed remote-head lookup must never permit report publication; a superseded main revision must skip publication.
- A passing PR on an older base does not qualify a newer combined main revision.

## Task 1: Restore a qualified predecessor reference

- [x] Audit original CI 45030696246 and artifact 14764099341 against integrated main `9d1d4ac0ece4fd056cf9f952795772feba6baa82` and exact tree `eb8e739a8084b063c5f480837fc1da2f78cbcd48`.
- [x] Retain native check/complexity, prior baseline, projected snapshot, rejected main diagnostics and provenance under `ai-knowledge/baseline-history/2026-11-22-main-9d1d4ac/`.
- [x] Update the active snapshot and concise provenance section in `docs/ai-knowledge.md`; verify unchanged policy and native projection.

## Task 2: Preserve main verification and current reports

- [x] Add regression coverage in the existing Maven workflow contract for main/PR cancellation behavior and stale publication.
- [x] Update `.github/workflows/gradle.yml` to cancel only obsolete PR runs and require a successful current-main check immediately before publication.
- [x] Verify the affected contracts, including failure of the old configuration and success of the repaired configuration.

## Task 3: Integrate and reconcile

- [x] Merge current main into the existing #1359 branch locally, preserve its SDK changes, and include the reviewed repair.
- [x] Run focused local verification of the combined source tree.
- [ ] Run the complete hosted CI for the resulting head.
- [x] Perform independent review of the combined source tree and original provenance artifacts.
- [ ] Perform ordinary merge without bypass and inspect the actual post-merge main jobs.
- [x] Update only evidence-supported issue checkboxes and current remaining-work notes; report any unfinished criteria explicitly.

## Verification before PR update

- The two new workflow tests failed against the original configuration; all 26 affected workflow tests pass after SDK integration, compiled with `javac -Xlint:all -Werror` on Java 25.
- The released extractor core reports 873940 context tokens (+12755), concept radius 121 (+2), normalized context debt 13.54 and no trend or claim failures. Its artifact lifecycle verifier, the repository artifact verifier, all eight coverage controls and the unchanged hotspot gate pass. The diagnostic used cached JDT 3.47.0 rather than pinned 3.41.0; its rejected-main control is byte-identical to the original hosted complexity report. This preflight does not replace pinned hosted Gradle CI.
- Full native local Maven verification could not start because the pinned clean plugin was absent from the offline cache. No dependency or test requirement was changed. Hosted CI remains required.
- Independent review rechecked all 293 original accepted artifact members, exact tested/integrated tree identity, rejected report bytes, preserved SDK changes and publication behavior for matching, stale, missing and failed remote lookups.
- Issues #661, #662, #874 and #1066 now distinguish completed scoped evidence from their remaining acceptance criteria. All ten parent issues remain open because none meets its full contract.
- Remote branch protection currently allows a stale base despite the repository's strict policy. The missing administrator setting is recorded in `docs/TODO-extern.md`; the existing policy is unchanged. Main CI retains the running revision and coalesces pending revisions to the latest one.
