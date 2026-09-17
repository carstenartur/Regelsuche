# Lazy matching for learned trajectory bindings

This experimental execution option follows #1009. It avoids constructing
unused matching constraints; it does not remove matching alternatives or
relax proof, binding, work-budget or route-selection checks.

## Entry points and controls

`RulePatternMatcher.matchSequenceLazy(steps, initialBindings, maximumWorkUnits)`
uses the same structural relation and depth-first order as `matchSequence`:
direct children, swapped children, then the existing repeated-operand
associative case. This is not a new general associative matcher.

`TraceStrategyDispatchLearner.trainBindingAwareLazy(formation, inputs, limits,
bindingLimits)` uses that matcher in actual prefix and complete-path checks.
The historical `train` and eager `trainBindingAware` remain unchanged controls.
The four profile names, production defaults and selection gate are unchanged.
Use the same arguments as the example in `binding-aware-strategy-dispatch.md`,
replacing only the training entry point with `trainBindingAwareLazy`.

Lazy models use `regelsuche.trace-binding-model/v3` and bind matcher revision
`regelsuche.rule-pattern-sequence/lazy-v1` in their identity. The containing
policy retains its existing v2 JSON shape. Eager models retain model v2 and
the historical matcher revision. No saved comparison identity is overwritten.

## What changes

Previously a commutative node eagerly constructed both swapped-child
constraints and the repeated-associative constraint, even when the direct
branch succeeded. Lazy execution stores immutable deferred descriptions of
those branches. It materializes their `Pending` constraints only when
backtracking actually visits them. Each description retains the original
binding environment and continuation, so later states can still overturn an
earlier choice without leaking tentative bindings between branches.

The counter still charges each constructed constraint, attempted match and
associative traversal/inspection. Every visited alternative is charged normally.
Work is lower when constraints are genuinely not constructed. Descriptor/frame
allocation, map copying, parsing, hashing, equality internals and numerical bit
complexity remain outside this logical node-work ledger. The figures below
are **not walltime, CPU-time or allocation measurements**.

With adequate work, the first complete substitution and failure relation are
unchanged. At a fixed smaller budget, lazy execution can finish where eager
execution runs out of work. A cutoff is still inconclusive and never exposes
a partial substitution. Prefixes and complete paths share one expansion budget.
Primitive engines, exact numeric seeds, scoped symbol identities, source-bound
provenance, replay and independent exact audits keep their existing roles.

## Development measurements

The control was remeasured before editing, using source `2cb856e` (the same tree
as merge `a387718`). Both variants were then run from freshly compiled Java-25
learning sources with identical arguments. These are development observations
on the unchanged eight selection-TRAIN inputs, **not held-out evaluation**.

| Logical work | Eager control | Lazy execution |
| --- | ---: | ---: |
| Direct `A+B` / `x+y` match | 9 | 6 |
| One diagnostic continuation: matching | 213 | 156 |
| Same diagnostic expansion: total | 233 | 176 |
| Template formation | 376 | 376 |
| Flat baseline over selection TRAIN | 235 | 235 |
| First route trial | 382 | 382 |
| Second route trial | 902 | 731 |
| Complete reported learning work | 2,332 | 2,161 |

The diagnostic input is `((x+y)*(x-y)+y*y)+101`, using
`UNGATED_CONTINUATIONS`. It still emits the actual three-step primitive path.
Both variants learn exactly one template with identical supporting evidence.
Every corresponding trial returns the same scores, and **neither variant
accepts a learned route**: even the cheaper second trial remains more costly
than the 235-unit flat baseline. This optimization removes overhead but does
not establish useful learned acceleration on this small TRAIN set.

The unchanged eager control hash is
`sha256:1cec3d6f2554c077e5595bbdf9b3898df922f338f105dbca5c82f64c690a3c46`.
The historical unbound policy still hashes to
`sha256:170363d5facca1ff53615fa8197bf70df268a9d872e0915eb5f228c42cddaf43`.
The new lazy policy hashes to
`sha256:107e89acc952b3ae56b9dbd06f157b15f3a1329f4fb37f6879808b6dd42426d2`.
The eager policy JSON was also compared byte-for-byte with the pre-edit control.

## Verification and remaining boundary

`RulePatternLazySequenceMatcherTest` compares both matchers, checks an
independent assignment oracle, exercises nested/swapped/repeated-associative
choices, exact seeds and scoped symbols, and tests every integer work allowance
through completion for its differential cases. `LazyBindingStrategyDispatchTest`
checks real execution, primitive replay, wrong-occurrence rejection, budget
fallback, deterministic training, strict utility selection and both old hashes.

Test-first scaffolds compiled but delegated to the unchanged eager execution.
They failed the saved-work regression and three dispatch integration assertions.
After implementation, all 64 focused matcher/model/dispatch tests pass with no
failures, skips or aborted tests. All 251 learning production sources and 162
test sources compile into fresh output directories under Java 25. An attempted
full local module run exceeded the invocation timeout; a full Maven/CI result
is not inferred from the focused run. Ordinary current-head CI and review remain
separate requirements before merging.

The pre-existing producer-AST transport limitation is tracked in #1010, not
fixed here. A changed compound grouping at the primitive engine's text boundary
still causes binding rejection and primitive fallback; the new integration tests
retain this negative control. No frozen corpus, reference, acceptance threshold,
proof check, existing workflow or repository protection is changed.
