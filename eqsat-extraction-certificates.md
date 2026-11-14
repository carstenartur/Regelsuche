# Bounded extraction certificates

The explicit proof API certifies equality between the exact input expression and
the returned expression, relative to caller-admitted rewrite axioms. Complete
means this equality has a fully checked derivation. It makes no claim of complete
normalization, optimality proof, speedup, or mathematical proof of the rules.
Existing saturation APIs and constructors retain their previous behavior.

## Direct Java API

This example uses only an exact native base pattern rule. The receiving caller
must provide trusted definitions of its own; receiving a rule ID or the producer's
rule registry is insufficient authorization.

```java
import de.regelsuche.ast.*;
import de.regelsuche.egraph.*;
import de.regelsuche.egraph.proof.*;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.*;
import java.util.List;

var a = new PatternExpr.Placeholder("a");
var zero = new PatternExpr.LiteralNumber(ExactRational.ZERO);
var source = new PatternExpr.Operation(BinaryOperator.ADD, a, zero);
List<RewriteRule> trustedRules = List.of(new PatternRewriteRule("add-zero", source, a));
Expr input = new BinaryExpr(new VariableExpr("x"), BinaryOperator.ADD,
    new NumberExpr(ExactRational.ZERO));
var options = EqualitySaturation.ProofOptions.defaults();
var runner = new EqualitySaturation(trustedRules, EqualitySaturation.Config.defaults());
var result = runner.saturateWithProof(input,
    node -> node.symbol().startsWith("op:") || node.symbol().startsWith("fn:") ? 1 : 0,
    options);
if (result.proof().complete()) {
    var certificate = result.proof().checked().orElseThrow().certificate();
    byte[] persisted = ExtractionCertificateCodec.encode(certificate, options.replayLimits());
    // A receiver supplies its own expected endpoints and trusted rule definitions.
    Expr expectedOutput = new VariableExpr("x");
    var decoded = ExtractionCertificateCodec.decode(persisted, options.replayLimits());
    var received = ExtractionProofChecker.check(input, expectedOutput, decoded,
        trustedRules, options.replayLimits());
    if (received.status() != ExtractionProofChecker.Status.ACCEPTED) {
        throw new IllegalStateException(received.detail());
    }
}
```

The checker has no live graph dependency. Rule IDs select definitions whose full
source and target are checked. Rational values and lossless scoped variable names
are exact identities; display labels and class IDs are never proof premises.
Constructing certificate records alone does not verify them. Only an accepted
checker result creates a `CheckedCertificate` token. Complete result wrappers
require the identical output object retained by the token; the fresh runner also
binds the identical invocation input. To attach evidence to a separately rebuilt
expression, run the checker again with that expression as expected output.

## Native scalar AC representation

`EGraph.exactScalarAcV1()` selects the versioned exact-scalar laws for addition
and multiplication. Insert an AST with `addExpression` or insert a canonical
`ExprValue` through `ENode.ExprValueAdapter`. Both store a whole associative
product or sum as one e-node whose children are sorted canonical class IDs.
The children list preserves repeated operands. Subtraction, division, powers
and arguments of functions retain their positions. Supplied value operators
must agree with the scalar profile, including their arity and laws.

For `a + b + a + c` this yields four e-nodes, including the three distinct
leaves. A product of 1,024 occurrences of `x` uses two e-nodes and 1,024
child references. These counts measure graph structure. Extraction charges
the chosen per-node cost plus each child occurrence and materializes a
left-associated binary AST in class-ID order. Within one graph, re-adding a
different grouping or permutation reuses the same e-class.

This profile supports insertion, union, congruence repair and extraction.
The syntax-directed saturation runner rejects a nonempty rule inventory
on this profile. Its proof runner owns a fresh ordered graph. Native
multiset matching and proof steps authorizing AC rearrangement are tracked
in [#662](https://github.com/carstenartur/Regelsuche/issues/662).

## Persisted format

`ExtractionCertificateCodec` reads and writes certificate data in binary format
`EQSC` version 1. Decoding returns an `ExtractionCertificate`. The receiver
passes it to `ExtractionProofChecker` with both independently expected endpoints
and its own trusted rules. Rule definitions in storage provide exact matching
data for replay. The format carries no checked token or trust decision.

All counts and indices are four-byte big-endian integers. Node and step tags
are one byte. Strings have a code-unit count followed by big-endian Java UTF-16
code units, which preserves exact labels and scoped identifiers. The format is
the four-byte `EQSC` marker and version integer, followed by these sections:

| Section | Version 1 layout |
| --- | --- |
| Terms | Count and nodes in certificate order. Tags 0–3 mean number, variable, binary operator, function. Each node has a label and a counted list of prior term indices. |
| Rule patterns | Count and nodes in postorder of first occurrence, visiting each rule's source then target in rule order. Equal nodes share an index. Tags 0–3 mean placeholder, number, binary operator, function. Each node has a label and a counted list of prior pattern indices. |
| Rules | Count, then each ID and its source and target pattern indices. |
| Proof steps | Count and steps in certificate order. Tags 0–4 mean reflexivity (term), symmetry (premise), transitivity (first premise, second premise), congruence (left term, right term, counted child proofs), pattern instance (rule, before term, after term, counted bindings of name then term). Binding names are in Java string order. |
| Endpoints | Input term, output term, conclusion proof step. |

The reader rejects unsupported versions, malformed fields, forward references,
noncanonical pattern or binding order, truncated input, and trailing bytes.
The codec applies a 16 MiB byte ceiling, plus the supplied `ProofLimits` to
storage work, syntax, and allocations. Successful encoding reserves the
additional read-side validation work so the same limits permit decoding.
A limit failure raises
`ExtractionProofBuilder.LimitExceeded`. Malformed data raises
`IllegalArgumentException`. Use a separately sufficient `ProofLimits` budget
for the detached replay itself. The byte API checks lengths before decoding.
Callers reading files or streams should also bound the initial read.

## Admission and outcomes

Proof mode admits only objects whose runtime class is exactly
`PatternRewriteRule`, using exact recognition and declaring construction
preservation. Supported patterns are Placeholder, LiteralNumber, Operation and
Function. It rejects subclasses, callbacks, LiteralVariable patterns, other
recognition profiles, non-preserving rules, duplicate IDs and target placeholders
absent from the source. No user rule callback runs during building or checking.
Scalar numerators and denominators must be immutable exact `BigInteger` base
instances. This boundary does not establish the mathematical truth of a rule;
that remains the trusting caller's responsibility.

`ProvedResult.expression()` is the accepted output on `COMPLETE`, and the exact
original input on every unavailable outcome. Statuses distinguish
`UNSUPPORTED_RULE`, `LIMIT_EXCEEDED`, `REPLAY_REJECTED`,
`EXTRACTION_UNAVAILABLE` and `UNSUPPORTED_OBJECTIVE`; legacy runs report
`NOT_REQUESTED`. `diagnosticResult()` is optional and contains only actual run
statistics assembled within the bounds. Its candidate expression can differ from
the returned original input when evidence is unavailable. Absence does not imply
zero work or a graph fixpoint. A directly certified AST can be outside parser
text limits; diagnostic formatting may therefore be unavailable.

Search opts in with `new EqualitySaturationStrategy(config, options)` and exposes
`lastProofOutcome()`. Only the operator-count objective is supported in this
mode; other objectives preserve the root. Bounded rendering and parser-compatible
symbol checks are required before publishing a text state. Proof-mode text
parenthesizes every binary node and preserves function argument order, so parsing
it retains the checker's exact endpoint tree, including right-nested addition
and multiplication. Negative numeric leaves and fraction-form numeric leaves
cannot be represented as the same leaf by the existing parser: unary minus and
division produce binary nodes. Those cases preserve the original search state
with `EXTRACTION_UNAVAILABLE`, as do other unrepresentable endpoints. A rendering
limit also preserves the original state with an explicit outcome. Checked
diagnostic provenance may still describe an AST when its search text cannot be
represented. Existing non-proof formatting is unchanged.
The synthetic `equality-saturation` path edge remains non-executable and is never
marked construction-proven. A certificate is an equality DAG, not one primitive
rewrite step.

## Bounds and costs

`ProofLimits(maxTerms, maxProofSteps, maxReferences, maxCharacters,
maxRationalBits, maxDepth, maxExpandedNodes, maxWork)` uses nonnegative limits;
zero makes that resource unavailable. Defaults, in order, are
`50_000 / 100_000 / 500_000 / 1_000_000 / 4096 / 256 / 100_000 / 5_000_000L`.
Capture/build limits and replay limits are separate. Graph node/iteration limits
remain separate as well. Unique term counts do not substitute for expanded tree
occurrence bounds: repeatedly sharing a child can still describe an exponential
output. Input, patterns, output materialization, depth, references, cumulative
characters, rational size and owned work are checked before their corresponding
expensive operations. The core 4096-character rational envelope still applies. The fresh native runner
also enforces a depth ceiling of 256, even when a larger capture limit is supplied;
the standalone proof kernel can use larger depth limits.

The producer captures every actual new semantic native rewrite union using finite
anchor terms and frozen placeholder bindings. Congruence reconstruction derives
repair equalities from those ground instances; raw graph unions are never axioms.
This store is independent of the old 256-witness diagnostic cap. Proof mode skips
legacy native witness extraction and reports `nativeWitnessExtractions == 0`;
`droppedWitnesses` accounts for omitted local witnesses. Capture loss is terminal
for proof availability, and pending graph repair completes.

Capture, reconstruction and detached replay are additional work.
`captureWork`, `buildWork` and `replayWork` report separate phase counts; the first
two share one monotone allowance. Failed rule-registry preflight retains the
work already consumed, even when the builder constructor does not return;
rejected charges are not counted as completed work. Search symbol checking and measured output
rendering use the remaining capture/build allowance, with traversal work and
emitted character counts kept separately. `lastProofOutcome().captureWork()`
includes this additional search work for complete and unavailable outcomes.
Diagnostic provenance retains the direct API outcome for its checked AST; it can
therefore have lower capture work than the search outcome and remain complete
when the search outcome reports unavailable text. No unbounded formatting
fallback runs after exhaustion. These counters are not retained-operation,
search or learning cost metrics. Existing native matching can materialize
combinatorial match sets, and arbitrary cost callbacks cannot be preempted. No
global matcher, total memory or whole-call execution bound follows from these
proof limits.

Legacy local witnesses remain diagnostic and unproved as an extraction chain.
They do not include every congruence bridge from original input to extracted
output. Their replay trusts rule callbacks. Only explicit complete evidence
changes `hasExtractionProof()` to true. The storage format covers the same
exact native base-pattern fragment as the detached checker.

## Conditional factor cancellation

`saturateUnderAssumptions(input, assumptions, costOfNode)` takes an immutable
snapshot of the caller's assumptions and owns a fresh ordered graph for that
invocation. With the core `ast_cancel_division_factor` rule selected, the
native matcher joins the two occurrences of the divisor by e-class identity
in either product position. For instance, `(y*x)/x` extracts `y` when the
context declares `x > 0`. A run with an empty context keeps the division.

The rule accepts exact rational literals and individual variable identifiers
as guard subjects. It records local evidence with each ground decision,
including the evaluator profile digest, context signature and truth value.
Zero is rejected. Missing or conflicting evidence and compound or otherwise
unsupported subjects remain undecided. Once 256 decisions are retained, further
conditional unions are blocked. The stop reason distinguishes undecided guards
and exhausted guard evidence from an attained fixpoint.

Use the returned expression together with its assumption list. These decisions
authorize local unions in the fixed context. Independent extraction replay for
conditional derivations and solver-certificate references are tracked in
[#662](https://github.com/carstenartur/Regelsuche/issues/662).
