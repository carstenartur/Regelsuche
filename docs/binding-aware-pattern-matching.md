# Shared bindings for learned pattern matching

The existing `RulePatternMatcher` now preserves alternatives until every later
constraint has been checked. For example, matching `(A+B)*(A-B)` against
`(y+x)*(x-y)` must revisit the first addition's locally successful binding and
return `A=x, B=y`. The same applies across function arguments and sequence steps.
The implementation uses explicit pending constraints and alternative frames;
traversal does not add one Java stack frame per pattern node.

## Typed sequence API

```java
var patterns = new RulePatternParser();
var expressions = new ExpressionParser();
var matcher = new RulePatternMatcher();
var result = matcher.matchSequence(
    List.of(
        new RulePatternMatcher.MatchStep(patterns.parse("A+B"), expressions.parseTerm("y+x")),
        new RulePatternMatcher.MatchStep(patterns.parse("A-B"), expressions.parseTerm("x-y"))),
    Map.of(),
    10_000);
```

`MATCH` returns one immutable shared substitution. `NO_MATCH` means the existing
structural relation was exhausted; it does not mean algebraic inequivalence.
`BUDGET_EXHAUSTED` is incomplete and returns no partial bindings. The sequence
contains 1 through 64 steps; the caller supplies a positive maximum work count.
Seed bindings constrain all steps and are never mutated. The typed single-step
`matchExpression(pattern, expression, initialBindings)` overload also accepts them.

For scoped input, pass the already bound `SymbolicExpression.expression()` AST.
Placeholder equality uses actual expression equality, including symbol IDs;
reloading the same ID and aliasing remain valid, while equal display labels from
different scopes do not establish equality. No display-text round trip is used.

Search order remains direct binary children, commutative swap for ADD/MUL, then
the existing repeated-placeholder associative special case. This is not a new
complete associative-commutative or algebraic matcher. Every alternative shares
an immutable snapshot of its prior bindings; extensions are isolated copies.

## Work and authority boundary

The `regelsuche.rule-pattern-sequence/v1` counter charges constraint construction,
attempted pattern-node matches and associative traversal/inspection. Generated
but unused alternatives are charged too. The limit is checked before each charge,
so the reported count never exceeds it. Parsing, allocation, map-copy and equality
internals, and coefficient bit complexity are outside this logical work ledger.
It is not a walltime, CPU or physical-memory quota. Existing optional-return
entry points keep their signatures and have no newly restrictive finite budget.

Matching determines applicability, not correctness or authorization of a learned
rule. Primitive replay, assumptions, proof verification and policy admission still
belong to their existing authorities. This API does not itself choose a learned
continuation or promote a new search policy.

## Test-first evidence and remaining integration

On unchanged production at test-only commit
`27b335b8fdc2b27183f8286dd71bdd9dd42dd821`, Java-25 CI run `35054095152`,
Maven job `104660483534`, ran **702 learning tests with three failures, zero
errors and zero skips**. The failures were the new sibling, function-argument
and repeated-composite backtracking cases. The other three new controls and
six existing matcher/scoped-symbol tests passed. Earlier versions of this page
incorrectly reported 1,183 learning tests and ten existing matcher tests; the
numbers here are corrected from the actual Maven log.

On implementation commit `89ebe23cfe68e2053be831b553c0e7b7724bf4c6`,
CI run `35055196980`, Maven job `104664044120`, all six backtracking tests,
two entry-point tests and six existing matcher/scoped-symbol tests passed.
The learning module ran **716 tests with zero failures and one error**: the alias
test used the invalid display label `shown-y`, so `SymbolScope.requireName`
rejected the fixture before matching. The fixture now uses the valid label
`shownY`; symbol-name validation and all matching assertions remain unchanged.
This establishes the original bug's red/green regression evidence, not a green
full product build. The correction requires a fresh current-head CI run.

The 20 new test methods also cover complete cancellation traces, initial
bindings, scoped IDs and aliases, exact success/failure work boundaries, input
bounds, entry-point compatibility and all 81 two-operand input combinations over
three variable names against an independent assignment oracle. Ordinary CI and
review remain required; no local compilation is claimed while the local
execution environment is unavailable.

Binding-aware dispatch, explicit occurrence selection and full CLI/HTTP symbol
integration remain the next steps of the approved roadmap, recorded in
`superpowers/specs/2026-09-16-binding-aware-learning.md`. Frozen studies, their
historical references, production defaults and all gates remain unchanged. These
matcher tests do not demonstrate a new learning gain or speedup.
