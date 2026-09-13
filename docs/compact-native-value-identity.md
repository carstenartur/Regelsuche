# Compact value identity for local native rewrites (#661)

This opt-in slice keeps a bounded owner of compact structural identities beside
the existing syntax AST. Its consumer is a local rewrite session opened by
`PreparedAstRewriteTransformationEngine.openValueSession(...)`: the session
executes a selected native rule at an occurrence issued by its current AST root.
It carries the resulting AST and owner-local value ID into the next step.

```java
var engine = new PreparedAstRewriteTransformationEngine(List.of(addZeroRule));
try (var arena = new CompactValueArena();
     var session = engine.openValueSession(
         arena.project(new ExpressionParser().parseTerm("(x + 0) * (x + 0)")),
         AssumptionSignature.ofExpressions(List.of()),
         new NativeValueRewriteSession.Budget(4, 4, 4, 100))) {
    var step = session.apply(session.current().occurrence(List.of(0)), 0);
    if (step.status() == NativeValueRewriteSession.Status.APPLIED) {
        Expr rewrittenAst = session.current().syntax();
        var localValueId = session.current().value();
        Transformation legacy = session.exportLegacy(step); // explicit text/hash boundary
    }
}
```

`addZeroRule` is the existing native `ast_add_zero_right` rule from
`AstRewriteTransformationEngine.defaultRules()`. The caller owns the arena;
closing one session does not invalidate other sessions using that owner.

## Design and identity boundary

`CompactValueArena` interns keys containing exact scalar/operator data and direct
integer child IDs. AC operands retain multiplicities; ordered operators retain
argument roles. Hash-table collisions require exact structural key equality.
IDs are opaque, owner-bound handles, not persistence keys. No global pool exists.

The value relation follows `ExprValueFactory.fromExpr`: associative/commutative grouping
and order are normalized, exact numeric subtraction and nonzero numeric division
are folded, and other syntax retains its operator structure. It does not infer
algebraic equivalence, definedness, guards or proof. Partial expressions are not
identified with total constants merely because their terms might cancel.
The new direct constructors and AST projection share the same scalar-folding
entry point, so constructing `3 / 2` directly or projecting that AST issues one
value. The existing `ExprValueFactory` construction methods remain unchanged.

The syntax projection caches immutable AST objects by reference. Local replacement
uses the existing `TreePosition.replaceAt` authority. Unchanged siblings keep
their AST identity and previously interned values; only new replacement objects
and copied ancestors require projection. An issued occurrence retains its full
source projection and path. Equal values never erase separate occurrences.
An affected AC ancestor can still require merging a wide multiplicity map; this
is not a constant-time local-update claim.

Stable structural digests are explicit, lazy exports under
`regelsuche.compact-value-digest/v1`; they are independent of allocation order.
Local interning does not use a digest as equality authority. Existing recursive
`ValueKey` and transformation exports remain available only at explicit export
boundaries and retain their old bytes and computation costs.
String payloads in digest preimages use length-framed exact UTF-16 code units, including unusual Java
names containing unpaired surrogates; normalized rationals use signed numerator
and positive denominator bytes. Child digests are ordered by argument role or,
for AC values, digest and multiplicity. Numeric owner-local IDs are never encoded.

## Execution and bounds

The local session admits exact `PatternRewriteRule` runtime classes; custom or
subclass dispatch is rejected. It invokes the existing bounded matcher and target
instantiator, then replaces the selected AST occurrence. Matcher inconclusiveness,
failed instantiation and arena exhaustion produce no accepted new current root.
Already performed work remains counted. Arena limits bound value entries, cached
syntax objects, integer child slots and retained scalar/operator payload bytes.
They are not measurements of Java heap allocation.
Unexpected unchecked failures propagate; the current root changes only after
successful projection. A failed admission can retain bounded, already interned
subvalues and their actual projection counts, but issues no accepted target.

The session has separate bounds for attempted matches, changed primitive rewrites,
copied ancestors and matcher branches. It is a selected local executor, not the
batch engine's candidate enumeration or canonical-size filter. Its operation
counts use `regelsuche.compact-native-rewrite-work/v1`; they are not the historical
cursor/search work scale, CPU time or a complete count of matcher internals.

Initial assumptions remain on the session; this unconditional native inventory
does not discharge them. Value IDs carry neither assumptions nor controller,
path, score, depth or proof labels. The session does not merge search states or
change any existing repeated-rule policy. AC-equivalent syntax changes still
execute and retain their actual occurrence and step.

## Public component evidence

The focused controls exercise forced single-bucket hash collisions, exact scalar
boundaries, cross-owner refusal, AC multiplicities, ordered roles, and the legacy
value relation over 28 expressions and all 784 pairs. A local replacement beside
an unchanged 2,000-level function subtree projects exactly one copied ancestor.
Native execution controls retain unsuccessful, inconclusive, failed, budget and
arena-limited attempts, separate assumptions and session budgets, and explicit
legacy exports. A real negative digest control caught distinct Java names being
collapsed by UTF-8 replacement; exact code-unit encoding fixes that boundary.

Independent review controls also exhaust value capacity after the real ancestor
copies and one newly interned ancestor: the failed attempt retains that work and
its partial interned values without advancing the session. A spent ancestor
budget still permits a root rewrite; the exported two-step chain replays against
fresh native transformations and rejects a missing step. Nested Unicode operator
names, ordered roles and AC multiplicities remain distinct under forced key
collisions, while equal cross-owner structures retain equal exported digests.

The retained component comparison has 69 byte-identical legacy value, primitive
execution and BestFirst state/metric files (80,607 bytes). Fourteen new digest and
export files (3,378 bytes) agree in two fresh JVMs despite different preceding
arena allocations. These comparisons do not run or rewrite historical studies.

For a declared depth-128 unary-function chain, both representations contain 129
distinct values. The existing recursive keys contain 380,677 Java string code
units; the compact arena retains 128 integer child slots and 3,078 framed payload
bytes, with zero digest computations before export. These are different explicit
representation components, excluding map/object headers and transient work;
they are not a heap, allocation, runtime or matched-work search measurement.

## Remaining scope

On the audited predecessor, `ExprValueFactory` still builds recursive textual
keys and BestFirst parses/canonicalizes new expression strings before hashing
those keys. The current `TranspositionGate` already qualifies its lookup by
assumptions; depth is a path label, not part of that lookup key. BestFirst's
application history still affects legal successors and is not removed here.

Migrating a complete search owner to `SearchNodeKey`, controller-aware quotienting,
bounded non-dominated labels, persistence reload, allocation profiling and the
#620/#663 utility comparisons remain open. This slice changes no default,
historical v1 identity or protected study and establishes no runtime speedup,
representation coverage gain or completion of #661.
