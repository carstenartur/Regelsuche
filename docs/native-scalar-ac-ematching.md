# Native scalar AC e-matching: bounded opt-in slice of #662

`EqualitySaturation.saturateNativeAc(source, context, budget)` executes native
AC matching on the existing `EGraph` and independently checks concrete
polynomial equalities before merging classes and releasing an extraction.
This is an executable opt-in API in `regelsuche-egraph`, using its existing
core dependency. The default `saturate` method and Search profile do not select
it automatically.

## Source audit and compatibility

At base `1388aeec853b60c0e9f9b1a47fc5781693aad570`, `ENode.ExprValueAdapter`
expands AC multiplicities into binary chains. `EGraphPatternMatcher` matches
ordered children. `EqualitySaturation` directly applies ordinary pattern
matches, while custom executors use extracted representatives. Its Search
adapter's `equality-saturation` sentinel is not a formal proof.

This slice leaves those implementations, existing evidence identities and
default behavior intact. It adds native multiset matching **over binary
e-nodes**; it does not implement n-ary graph storage. Existing direct-matching
statistics cannot be relabeled as measurements of this new semantics.

New contracts:

| Contract | Meaning |
| --- | --- |
| `regelsuche.scalar-polynomial-ac-ematching/v1` | Privately compiled typed match program, deterministic query and logical work observations. |
| `regelsuche.native-scalar-polynomial-saturation/v1` | Fresh graph, frozen rounds, unchanged scalar context, checked applications and final release. |
| `core.PolynomialNormalizer/exact-Q-normal-form-pair/v1` | Two real calls to the existing final exact normalizer followed by exact AST equality. |

The plan hash binds typed source syntax, including ordered roles and literal
values, under the new AC contract. Existing rule content hashes retain the
original rule, recognition profile and metadata. New semantics do not rename
the rules or alter their historical identities.

The new local JSON boundaries escape unpaired UTF-16 code units losslessly before
UTF-8 encoding and hashing. Distinct Java placeholder names remain distinct in
plan, binding and complete run identities, including names that are not Unicode
scalar sequences. Ordinary existing JSON bytes stay unchanged. The retained
historical rule hash is not used for lookup, deduplication or
union authority; execution retains the actual rule and compiled plan.

## Admitted mathematical and matching fragment

The caller explicitly declares each variable a **commuting rational scalar** in
an immutable `ScalarContext`. Merely spelling an expression with `*` does not
declare arbitrary objects commutative. Undeclared variables, matrix/function
sources, division and powers with nonliteral, zero, negative or greater-than-8
exponents are excluded. Numeric leaves use `ExactRational`, with at most 1024
bits in numerator and denominator. `SUB` and `POW` retain ordered child roles;
only `ADD` and `MUL` have AC matching semantics.

For example:

```java
var context = new NativeScalarPolynomialSaturation.ScalarContext(
    List.of("a", "b", "c", "d"), List.of("a != 0"));
var result = new EqualitySaturation(rules).saturateNativeAc(
    parser.parseTerm("a*b+c+a*d"), context,
    NativeScalarPolynomialSaturation.Budget.defaults());
```

The context preserves the supplied facts and variable declarations exactly and
also retains the existing normalized assumption signature. This first context
contract accepts only declared-variable facts `x = 0` or `x != 0` (including the
existing signature's equivalent nonzero spellings). Opposite facts for one
variable are rejected. Other predicates/domains are explicitly unsupported.
Missing nonzero knowledge is never promoted to true. No fact discharges a rule
guard in this slice: every admitted equality must hold unconditionally on the
declared scalar domain. The fresh internal graph therefore has unconditional
classes, and every exact check binds the complete external context hash. No
context facts are introduced, removed or merged between runs.
The standalone matcher rejects incoming e-classes with nonempty assumption
signatures; it cannot silently erase a pre-existing graph context.

Only the exact base `PatternRewriteRule` implementation is eligible, with
equivalence-preserving metadata, no emitted or inferred guard, admitted source
and target patterns, declared literal variables, and no unbound RHS
placeholder. Metadata alone cannot authorize a union. Custom executors,
subclasses, conditional/nonpolynomial rules and unsupported targets remain
visible as exclusions; there is no representative or reference-bridge fallback.

The matcher compiles a program once per admitted rule, then traverses the
actual e-class alternatives. Same-operator children flatten into operand
multisets. Joins consume multiplicities and equate repeated placeholders by
canonical e-class identity. They do not generate syntax permutations or select
a cheapest AST. AC at the query root may match a submultiset, retaining every
unmatched operand for contextual replacement. Nested AC programs match an exact
operand multiset. A placeholder binds one atomic e-class, **not an arbitrary
group or remainder**. This is the scope of the completeness claim.

## Application and output authority

Each round fixes the current graph version and class set, collects every
admitted rule's query, and applies nothing until **all queries in that round
are complete**. A partial, cyclic, unsupported or failed query is retained and
the whole round is discarded for application. Already completed earlier
rounds remain visible.

For each retained match the consumer:

1. Extracts the concrete bound operands and instantiates the real source rule.
   It calls that exact executor's `matches`, `assumptions` and `apply` methods.
2. Executes the independent `PolynomialNormalizer` on both actual primitive
   sides. A missing normal form, unequal result or failure cannot authorize a
   union.
3. Reattaches the retained AC remainder, preserving multiplicity, and executes
   a second exact pair check against the current matched class's extraction.
4. Admits the bounded graph mutation and only then inserts/unions/rebuilds.
5. Before releasing any final expression, freshly normalizes both the original
   source and extracted output under the same retained context binding.

Checks retain actual inputs, normal forms or explicit missing values, typed
status, checker identity and context. Application rows bind the original rule
hash, compiled plan hash, concrete e-class binding/remainder and both checks.
The private result retains requested rule hashes in order, classifications,
rounds, applications, input/output, budgets and content identity. Canonical JSON
is an observation receipt; its SHA-256 is not proof authority or an untrusted
receipt verifier. Formal proof DAGs are explicitly `NOT_PRODUCED`. Existing
congruence rebuilding is used; `directUnions` counts checked rewrite unions,
not an invented count of formal congruence proof edges.

`admittedFragmentFixedPoint` is true only after a fully matched and fully
processed round makes no graph change. `requestedInventoryComplete` separately
reports rule admission. Round limits and cycles never become saturation
claims. A valid final extraction can be released after incomplete exploration
only if the fresh final exact check succeeds. Refusal or unavailable final
normalization releases no expression, including when the candidate happens to
equal the source. Input material that could not pass bounded admission has an
explicit unavailable receipt field; it is not a complete bound run artifact.

## Bounds and work

Defaults: 4 rounds, 512 graph nodes, 128 expression nodes, depth 32, 8 flattened
AC operands, 256 retained/intermediate matches per query, and 200,000 shared
logical units. The configurable hard ceilings are 16 rounds, 4096 graph nodes,
256 expression nodes, 32 AC operands, 4096 query results and 10,000,000 logical
units. At most 32 rule objects and 64 nodes per compiled pattern are accepted.
An iterative encoding preflight checks each source/target pattern's unfolded
node count, names, numeric widths and function arity before the existing
recursive rule fingerprint sees it. This also bounds rejected pattern material
and runs whose logical budget is zero. Material outside these limits receives
a null requested/rule hash with an explicit unavailable status, rather than a
fabricated digest. This encoding preflight belongs to the explicitly unavailable
canonical-encoding internal work; accepted logical dispatch counters are unchanged.
The standalone matcher also refuses graphs or supplied root sets above 4096
before ordering candidates. AC recursion is bounded at 64; cyclic alternatives
are inconclusive rather than silently skipped. Numeric/symbol/arity bounds are
checked before ordered node snapshots parse or render their payloads.

One non-resetting run authority pre-admits actual logical events: input and
context visits, compiler/replay/normalizer/extraction dispatches, native node
visits, multiplicity selections, joins, retained results and mutation batches.
Query work snapshots are cumulative under this authority, not independent
per-query caps. Compilation programs additionally report their actual syntax
node visits. A refusal retains the consumed prefix; a final check may retain
its completed left normalization while refusing the right dispatch.

Graph insertion uses a conservative AST-node upper bound against graph
capacity. This is a capacity admission, not a measured allocation count.
PolynomialNormalizer retains its existing 1000-term and 4096-bit coefficient
limits. Its inner arithmetic is **UNAVAILABLE**, as are inner extraction,
rebuild, primitive executor, canonical encoding and hashing work. The rule
inventory consists of existing trusted executor objects, not a newly added
hostile-input metadata loader. These logical events are not a measured total
CPU/allocation/arithmetic ledger; `totalWork` is **UNAVAILABLE**. No comparative
matched-total-work, timing gain or mathematical proof-certificate claim follows
from this slice.

## Ordinary controls and remaining #662 work

`ScalarPolynomialAcMatcherTest` compares every word of lengths 2–4 over
`{a,b,0}`, every binary parenthesization, both AC operators, and patterns
`X op X`, `X op Y`, `0 op X` against a separate complete permutation oracle:
`2 * 3 * (9*1 + 27*2 + 81*5) = 2808` queries. It also checks repeated bindings,
non-cheapest e-class alternatives, ordered subtraction, unsupported sources,
retained partial results, malformed exponents, graph admission and cycles.

`NativeScalarPolynomialSaturationTest` executes non-adjacent factoring that the
old ordered path misses; rejects false idempotence metadata before union;
discards a partial round despite an earlier complete rule's matches; preserves
contexts and exclusions; checks missing scalar declarations, guard subclasses,
zero/nonzero facts, repeated-variable near misses, ordered subtraction, budget
prefixes, a real normalizer expansion refusal, final dispatch refusal and a
real cycle following valid zero removal. Old EGraph, saturation soundness,
scalability, Search strategy and normalizer tests remain regression controls.

Independent admission controls cover 5,000-level source/target patterns, shared
and wide patterns, oversized names, a zero-budget invocation and numeric
payloads that must not be rendered before admission. Separate controls preserve
distinct malformed Java placeholder names through real checked rewrites and
lossless plan/run JSON identities.

Run ordinary controls with the repository's pinned Java 25/Maven toolchain:

```sh
mvn -o -pl regelsuche-search -am test \
  -Dtest=ScalarPolynomialAcMatcherTest,NativeScalarPolynomialSaturationTest,NativeScalarAdmissionReviewTest,EGraphTest,EqualitySaturationScalabilityTest,EqualitySaturationSoundnessTest,EqualitySaturationStrategyTest,PolynomialNormalizerTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

This does not close all of #662. Actual n-ary storage, general/grouped-variable
AC matching, incremental compiled joins across rounds, monotone e-class
analyses, guarded native unions with portfolio evidence, formal proof DAGs and
their independent checking, measured total work, broader extraction objectives
and two-host/container study qualification remain separate work. No study,
held-out inspection, default switch or CI gate change is part of this slice.
