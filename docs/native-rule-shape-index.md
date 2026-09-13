# Native rule/shape selection (#696)

`PreparedAstRewriteTransformationEngine.openShapeIndexedCursor(source)` is an
explicit alternative to `openCursor(source)`. It selects native pattern rules
through AST shapes before invoking the unchanged production
`EquivalenceAwarePatternMatcher.matchDetailed`. The batch engine, historical
cursor, `NativeIncrementalMoveProvider`, search policies and product defaults
keep their current behavior.

Both cursor APIs accept only exact `PatternRewriteRule` runtime classes.
Specialized subclasses and other rewrite implementations fail preflight; their
custom dispatch is never replaced by an inferred pattern contract. The declared
built-in control inventory contains the 15 exact pattern rules selected from
`AstRewriteTransformationEngine.allBuiltInRules()` in its original order.

```java
var engine = new PreparedAstRewriteTransformationEngine(explicitNativeRules, 12, 1000);
var cursor = engine.openShapeIndexedCursor(source);
try (cursor) {
    while (cursor.work().totalUnits() < totalGenerationLimit) {
        var next = cursor.next(totalGenerationLimit - cursor.work().totalUnits());
        if (next.isEmpty()) break;
        consume(next.orElseThrow());
    }
}
var receipt = cursor.indexReceipt();
```

The receipt after `close()` includes cleanup work and the final early-close
status; that cleanup can itself overrun the allowance. A cursor keeps the original source AST;
each emitted transformation is one rewrite of that source.

## Conservative selection

The index compiles immutable root buckets once during the first pull's setup.
Each reached occurrence looks up one root bucket and merges it lazily with an
already ordered wildcard list. The merge preserves inventory positions,
including duplicate entries. Children remain in the original preorder. It
does not scan every excluded rule or materialize all later candidates.

| Rule profile or pattern | Necessary condition used by the index |
| --- | --- |
| Root placeholder | Wildcard: every AST root is admitted |
| Algebraic binding inference | Entire rule is a wildcard, including literal-number and power patterns |
| Recognition-rule IDs or nonzero equivalence depth | Entire rule is a wildcard |
| Structural or pure associative/commutative profile | Number kind, exact literal-variable name, binary operator, or exact function name and arity |
| Exact profile only | Additionally, up to the first two immediate pattern children require their corresponding root shape; placeholders add no constraint |

Numeric values are not projected or compared by the filter. Literal numbers
therefore share one bucket and can produce ordinary false-positive admissions.
Function and literal-variable names retain exact spelling and case. Child
features are computed only when a selected exact rule needs them, then reused
within that occurrence's frame. No formatting, parsing, normalization, degree
analysis, algebraic expansion or recursive matching occurs inside the index.

These conditions follow the production matcher's dispatch: without algebraic
inference, non-placeholder roots must have the indicated type/operator or
function name/arity. Under the exact profile, child positions are fixed. AC
flattening and permutation do not justify positional child constraints, so those
are disabled for every non-exact profile. Algebraic inference can recognize
`A^2` in a multiplication or an instantiated constant operation in a numeric
leaf, so even apparently obvious root exclusions are disabled for that profile.
The recursive matcher remains authoritative after every admission, with its
unchanged branch limit and `INCONCLUSIVE` outcomes.

`RootSymbolTermRuleIndex` in the search module remains a separate older index.
Its AST query formats the subtree and selects manually registered string root
symbols; it does not supply this cursor's applicability or profile guarantees.

## Small versioned receipt

The normal `TransformationCursor.Snapshot` still binds source, ordered complete
rule definitions, recognition profiles, limits, attempts, emitted count and work.
The new receipt adds only the index revision and a list of reached occurrence
paths with the exact number of rules excluded by each root lookup. The plans
are deterministic from those existing typed rule definitions and the selection
revision; no parallel configuration or qualification authority is introduced.

| Bound surface | New revision |
| --- | --- |
| Index receipt | `regelsuche.native-rule-root-shape-index/v1` |
| Definition `orderRevision` | `regelsuche.native-occurrence-preorder-root-shape-selection/v1` |
| Cursor work | `regelsuche.native-transformation-cursor-shape-work/v1` |

The selection marker changes the bound execution definition while preserving
the output order. Old cursor JSON has no extra fields, and old work/order IDs
are unchanged. Three canonical old cursor snapshots captured before this change
remain byte-identical after sorted JSON serialization.

## Work and stopping

The new work map adds actual events for each compiled rule, inspected pattern
root, extracted or reused occurrence feature, bucket lookup, lazy candidate
selection and child predicate check. Existing `RULE_MATCH` counts actual
production matcher calls; `MATCHER_BRANCH` retains its actual reported branches.
An exact child rejection records `SHAPE_REJECTED` with zero matcher branches.
Ordinary `NOT_MATCHED` attempts expose false-positive matcher admissions.

Root-excluded rule counts are diagnostics computed from the disjoint bucket and
wildcard cardinalities. They are not reported as executed predicate calls and
do not add fictional mechanical work. Only reached occurrence lookups enter
the receipt. An early successful pull and close do not select later rules,
inspect their needed child features, visit descendants or execute later matches.

The allowance is checked after cheap selection and before entering the matcher.
If selection consumes it, the attempt records `MATCH_NOT_STARTED` and the cursor
ends `WORK_EXHAUSTED`. Cold setup (including index compilation), an individual
shape check and the existing matcher/materialization remain atomic and may
overrun their entry allowance. All actual named work remains visible. Candidate
caps, early close, invalid input, technical failures and matcher limits cannot
be presented as a complete bounded relation.

This remains an operation-event model. Matcher invocation counts and AC branches
are not a count of every recursive comparison, arithmetic operation or allocated
byte. Index bookkeeping, map internals, definition preflight, and the existing
atomic parser/formatter/canonicalizer/hasher internals are not a complete runtime
profile. Index compilation is per cursor, and feature reuse is per source
occurrence frame; cross-state index caching and ancestor feature maintenance are
future work. New total units do not establish matched physical work or speedup.

## Component controls and limits

The controlled source `((x+0)*1)+(y*0)` with the 15-rule native inventory emits the
same four transformations. It invokes the matcher 8 times instead of 135. Its
receipt records 113 root exclusions, 14 actual child rejections and 4 ordinary
false-positive matcher admissions. Compilation and feature/filter work are
reported separately. This is matcher-call avoidance, not an end-to-end timing
or total-work reduction claim.

The controls compare exact ordered production output on 92 source expressions,
and compare 675 pattern/profile/input combinations directly with the full
production matcher, including matches and inconclusive limits. They cover
function arity/case, placeholders, algebraic root changes, associative/AC
recognition, duplicates, growth/candidate caps, budget and close boundaries. An
actual BigInteger display-conversion probe fails if compilation or filtering
enters numeric formatting.

Independent review controls also retain a warm selection-budget overrun after
the first emitted transformation: the rejected child predicate keeps its actual
work, earlier receipts keep their prefix, and a later allowance cannot resume
the exhausted cursor. A nested AC limit remains inconclusive even when a later
function child cannot match; an algebraic coefficient limit likewise survives
a literal pattern whose root differs from the input.

```bash
mvn -o -pl regelsuche-core -am \
  -Dtest=ShapeIndexedCursorReviewTest,NativeRuleShapeIndexTest,ShapeIndexedTransformationCursorTest,PreparedTransformationCursorTest,PreparedAstRewriteTransformationEngineTest,EquivalenceAwarePatternMatcherTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The component test writes `regelsuche-core/target/shape-index-component-control.json`
and `shape-index-reference-control.json` for inspection. These are unit-control
receipts, not study or qualification artifacts. This slice does not activate a
new search default, close #696, establish universal completeness, or make a
runtime, depth, learned-ranking, transposition or `1000×` claim.
