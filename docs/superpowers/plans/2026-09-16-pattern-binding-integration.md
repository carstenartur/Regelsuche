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

- [ ] Observe the intended failures in the existing Java-25 CI, not just an unrelated build failure.
- [ ] Implement immutable pending constraints and alternative frames. Defer swapped and associative choices; revisit them when any later constraint fails. Preserve function-argument order and old successful selection order.
- [ ] Run existing and new matcher controls through ordinary CI; retain the failed predecessor evidence.

## Task 2: bounded shared bindings across steps

Files: the matcher facade/search above and new `RulePatternSequenceMatcherTest.java` in the same test package.

Interface: `RulePatternMatcher.MatchStep(RulePatternNode, Expr)`; `matchSequence(List<MatchStep>, Map<String, Expr>, long)` returns status, immutable bindings and charged matching work.

- [ ] Add cross-step tests for `A+B` against `y+x`, followed by `A-B` against `x-y`; require `A=x, B=y` rather than committing the first local match.
- [ ] Add same-label/different-scope rejection, alias acceptance, composite subtree equality, immutable seed/result and input-bound controls.
- [ ] Test exact success/failure work boundaries and a smaller allowance returning BUDGET_EXHAUSTED with no partial bindings.
- [ ] Check all small input permutations against an independent two-variable assignment oracle, without deriving expected results from the matcher.
- [ ] Implement the bounded sequence API using the same iterative search, with no change to the dispatcher or historical policy identity.

## Task 3: qualify and document the boundary

- [ ] Document the real callable sequence API and the remaining dispatcher/CLI integration.
- [ ] Inspect the published diff and current-head Maven/Gradle, external comparison, JMH, SymPy and code-scanning results.
- [ ] Seek independent review when the implementation is ready. Do not merge on incomplete evidence or claim a learning gain from matcher unit tests.

Ordinary reproduction:

```sh
mvn --batch-mode --no-transfer-progress -pl regelsuche-learning -am \
  -Dtest=RulePatternMatcherBindingBacktrackingTest,RulePatternSequenceMatcherTest,RulePatternMatcherTest,ScopedSymbolPatternMatcherTest test
```

The complete repository lifecycle remains required in addition to this focused command.
