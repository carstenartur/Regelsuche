# Typed primitive AST transport

`AstRewriteTransport` is an explicit in-memory primitive execution boundary,
revision `regelsuche.ast-rewrite-transport/v1`. It is the first implementation
slice of #1010, not a replacement of the string-based search runtime.

## Use

```java
var transport = new AstRewriteTransport(rules, maximumGrowth, maximumCandidates);
List<AstRewriteTransport.Step> candidates = transport.generate(sourceAst);
var chosen = candidates.getFirst(); // selection belongs to the caller
List<AstRewriteTransport.Step> next = transport.generate(chosen.target());
Expr checked = transport.replay(sourceAst, List.of(chosen));
```

The source and target are the actual immutable `Expr` values produced by the
primitive engine. Do not reconstruct subsequent inputs with display formatting.
A `NumberExpr` containing 1/3 remains one numeric node, not a DIV tree. Ordered
ADD/MUL grouping, function arguments and scoped symbol IDs likewise survive.

Generation delegates to the prepared engine's shared rule traversal. Exact
`PatternRewriteRule` instances still use its prepared matching; subclasses and
custom rules still use their actual overrides. Only the new typed output branch
is selected. It does not construct legacy display-based subtree hashes. The
historical string method keeps its output, filtering, ordering and identity.

Typed steps retain source, target, rule ID, kind, growth/cost metadata,
equivalence-by-construction flag, side conditions and attribution. Typed
source/target equality replaces the old display-based application-key hash;
that hash is not imported as a structural identity or occurrence certificate.
Generation uses structural no-op filtering and structural step deduplication,
so two trees are not discarded solely because they have the same display text.
Canonical AST size still supplies the existing per-step growth criterion.

## Replay and authority

The replay method checks the exact source of each recorded step and regenerates
candidates under the receiving engine's current rule set and bounds. The full
record, including target and metadata, must be present in the regenerated
candidate list. Equivalent-but-differently-structured sources or targets are not
interchangeable. A changed rule set or tampered metadata cannot merely be
accepted because a public `Step` record was constructed.

This establishes reproducibility relative to the supplied rules. It does not
prove an arbitrary custom rule correct or discharge its side conditions.
Existing mathematical proof/audit authorities still apply. The typed transport
does not fabricate `TransformationProvenance`, authorization, saved-policy
hashes or a persisted certificate from the in-memory records.

Input and output values have explicit limits of 10,000 nodes and depth 128;
a replay contains 1 through 64 steps. Candidate count must be positive and the
configured growth/candidate limits apply. Violating a structural limit throws
rather than returning a partial trace. These bounds are not a CPU, total
allocation or arbitrary-rule execution quota. The shared engine traversal
remains eager and can prepare more results than the returned candidate limit.
There is no new work accounting or performance claim in this slice.

## Verification

The ten initial transport/control tests were first executed at `e721861` with
a compiling scaffold that deliberately called the existing string engine and
reparsed its results. Source-pinned Java-25 run 35119304933, job 104872693443,
compiled successfully and ran 790 core tests: exactly five assertion failures,
no errors or skipped tests. The failures expose grouped ADD/MUL loss, numeric
rational-leaf loss on input/output and structure loss in function arguments.
The five other controls and all 780 existing core tests passed.

The implementation replaces that scaffold with direct typed generation. Further
controls cover growth/candidate limits, subclass dispatch, immutability and
source-bound replay. `TypedPrimitiveBindingTransportTest` generates actual
three-step primitive traces, learns the existing shared-binding model from two
separate training trajectories, and applies its full-state constraints to grouped,
rational and scoped inputs. Its legacy string-engine control retains the known
binding rejection. Mathematical equivalence is checked separately for the
grouped cancellation example; it is not used to bypass structural equality.

Current-head CI results are recorded in the PR discussion. Local execution was
unavailable during this implementation; no local compilation or test pass is
claimed. A successful module run does not replace full product CI and review.

## Remaining work for #1010

General search states, `CompiledLinearRewriteEngine`, live binding-dispatch
observations and persisted/source-hashed replay still use their historical
string transport. They are not migrated by the primitive API or its integration
tests. That migration requires carrying the typed states and a versioned
structural codec through those consumers, including real work accounting.
No default search path is silently redirected to this transport. #1010 remains
open until its end-to-end acceptance conditions are met. Historical studies,
policy controls, proof checks and utility thresholds are unchanged.
