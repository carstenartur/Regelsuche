# Pattern binding integration implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair lost binding alternatives and provide bounded, scoped cross-step matching.

**Architecture:** Keep `RulePatternMatcher` as the public facade. A package-private iterative binding search owns pending constraints, alternative frames and work accounting. Binding maps are copied on extension and never modified after sharing with a frame.

**Tech Stack:** Existing Java 25, Expr/RulePatternNode types, JUnit 5; no dependencies.

**Spec:** `docs/superpowers/specs/2026-09-16-binding-aware-learning.md`

## Global constraints

Preserve primitive rules, assumptions, proof checks, frozen studies, historical references, production defaults, build gates and repository protection. Sequence bounds are 1 through 64 steps and a positive caller-supplied work allowance. Report local execution limitations and qualify the exact published head.

## Task 1: restore delayed binding alternatives

Files: `regelsuche-learning/src/main/java/de/regelsuche/mining/RulePatternMatcher.java`, new sibling `RulePatternBindingSearch.java`, and `regelsuche-learning/src/test/java/de/regelsuche/mining/RulePatternMatcherBindingBacktrackingTest.java`.

- [x] Add real predecessor tests, including:

  ```java
  assertTrue(matcher.match("(A+B)*(A-B)", "(y+x)*(x-y)").isPresent());
  ```

- [x] Observe the intended failures in Java-25 CI: run `35054095152`, Maven job `104660483534`, test-only `27b335b8`.
- [x] Implement immutable pending constraints and alternative frames. Revisit deferred choices when a later constraint fails; preserve function-argument and direct-before-swapped search order.
- [x] Run the matcher regression controls through ordinary CI: all six pass on `89ebe23c` in run `35055196980`, Maven job `104664044120`. The full build is not green (see Task 3).

## Task 2: bounded shared bindings across steps

Files: the matcher facade/search above and `RulePatternSequenceMatcherTest.java` in the same test package.

Interface: `RulePatternMatcher.MatchStep(RulePatternNode, Expr)`; `matchSequence(List<MatchStep>, Map<String, Expr>, long)` returns status, immutable bindings and charged matching work.

- [x] Add cross-step order recovery and negative rebinding controls.
- [x] Add scoped identity, alias, composite binding, immutable seed/result and input-bound controls.
- [x] Exercise exact success/failure work boundaries and the smaller allowance returning BUDGET_EXHAUSTED.
- [x] Check all 81 small input combinations against an independent assignment oracle.
- [x] Implement the bounded sequence API without changing dispatcher or historical policy identity.
- [ ] Confirm the full 12-method sequence suite after correcting the alias fixture's invalid display name (`shown-y` to `shownY`). On `89ebe23c`, 11 methods pass and this fixture raises an error before matching.

## Task 3: qualify and document the boundary

- [x] Document the callable sequence API and remaining dispatcher/CLI integration.
- [x] Inspect the Maven failure on `89ebe23c`: 716 learning tests, zero failures, one invalid-label fixture error. Correct the documentation's predecessor counts to 702 learning tests and six existing matcher/scoped-symbol tests.
- [ ] Inspect fresh current-head Maven/Gradle, external comparison, JMH, SymPy and code-scanning results after the fixture correction. Downstream Maven modules were skipped on `89ebe23c`; their qualification is still required.
- [ ] Complete review and address current-head findings before merge. Copilot review was requested; a request is not approval.

Reproduce the learning module and its dependencies without filtering away other tests:

```sh
mvn --batch-mode --no-transfer-progress -pl regelsuche-learning -am test
```

The complete repository lifecycle remains required in addition to this module run. No successful local execution is claimed while the execution environment is unavailable.
