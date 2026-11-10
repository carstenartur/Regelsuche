# Bounded EqSat extraction certificates

## Intent and status

Give an embedding caller an independently checkable equality from its exact input to the exact expression returned by equality saturation. This is the next mathematical-correctness capability after PRs #1161, #1166 and #1167, all merged into accepted main `d191fc8abb998c14ea7bc5965401eaf696e8fc2a`. Main CI `42825000269` is successful. This document is an engineering design, not a claim that its implementation already passes.

The user authorized continued implementation of the identified gaps. The architecture below is the implementers' choice under that authorization; the earlier exploratory design was not a user-approved selection. No new module, language runtime or external dependency is needed. Production mathematics and proof checking remain Java 25 in the existing `regelsuche-egraph` module, which depends inward on core.

The first delivery supports the exact native pattern language already implemented by `EGraphPatternMatcher` and `EGraphPatternApplier`. “Complete” means every premise needed for this particular input/output equality is included and checked. It does not mean complete normalization, minimum-cost optimality, a proof that arbitrary supplied rules are mathematical theorems, native AC matching or conditional-context support.

## Existing source and resolved earlier defects

- `EqualitySaturation` uses exact native e-class matching for exact base `PatternRewriteRule` objects; native bindings refer to entire e-classes. Other implementations/profiles take the concrete representative path.
- `EGraph.repair` performs congruence unions in two paths. Canonical node keys are replaced, so final membership does not explain historical equality.
- `SaturationProvenance` holds at most 256 diagnostic local witnesses. Its extraction-proof flag correctly remains false today.
- `EqualitySaturationStrategy` exposes a synthetic observational edge, not an executable primitive rewrite, and explicitly falls back for unsupported extraction objectives.
- Scoped variables already travel through `VariableExpr.name()` as lossless `SymbolId` identifiers. Display labels must never become proof identities.
- PR #1166 already corrected DOMAIN/DOMAIN_MEMBERSHIP identity, UNKNOWN evidence and the evaluator revision to v3. It also propagates owned concrete matcher limits. These are resolved defects, not tasks to repeat here.

## Approaches and selected architecture

1. **Increase the local witness cap.** This cannot prove congruence or the final extraction, irrespective of the cap. Reject.
2. **Journal immutable reasons inside every insertion, hash-cons collision, union, repair and extraction path.** This can provide complete explanations, but requires invasive changes to mutable graph maintenance and proof-aware native match derivations. It remains a possible later performance optimization.
3. **Capture the actual exact ground rewrite instances, then reconstruct their finite ground congruence consequences.** Select this approach. A bounded producer builds an explicit equality DAG from immutable terms and captured instances. A smaller detached checker validates that DAG without consulting the live graph. It preserves native matching and avoids changing both repair paths.

This is not a second AST rule search: the reconstruction does not select rules, discover bindings, normalize expressions or invoke `EGraph`. Its only equality axioms are the already captured ground instances of caller-admitted rules. Congruence closure over the finite subterm closure of ground premises and queried input/output is sufficient to decide that ground equality. If reconstruction reaches its fixed point before a configured bound, it must find a derivation whenever those captured premises entail it. Exhausting a bound does not establish non-derivability; `NO_DERIVATION` is available only after complete closure.

The conceptual reference is Robert Nieuwenhuis and Albert Oliveras, [Proof-producing Congruence Closure](https://www.cs.upc.edu/~oliveras/rta05.pdf), especially the separation of equality/congruence maintenance from explicit explanations. The proposed simpler bounded producer does not inherit that paper's complexity or performance guarantees.

### Why captured instances cover native matching

Maintain one finite immutable **anchor term** for each newly allocated e-class. Its children are the current child classes' earlier anchor terms. The term DAG therefore has backward references and cannot become cyclic when the live graph later develops cycles. A canonical survivor already has its own finite anchor; no equality axiom is introduced by changing the representative ID.

For each actual native match, choose exactly one anchor term per placeholder binding before changing graph membership. Instantiate both rule patterns with that same map. Capture the resulting ground equation for every new semantic native union. Repeated occurrences of a placeholder must use the identical selected term ID.

Inductively, all finite expressions represented by a class are equal under the previously captured ground equations: insertion is syntax construction, hash-cons reuse and rebuild are congruence, and a native union adds one captured ground rule instance. Thus a native match using different equivalent child nodes is represented by the chosen ground instance even when its concrete LHS never appeared as a single original tree. This retains native child-combination semantics. The final ground closure rederives the needed congruences independently; it never treats a live class ID or a rebuild union as proof.

For example, after a captured `a = b`, a native match of `g(A,A)` may occur at the original term `g(a,b)`. Choosing `A = a` captures the exact ground rule at `g(a,a)`. Congruence supplies `g(a,b) = g(a,a)`, and the final certificate must include that premise chain. The detached checker will reject a missing bridge. In executable native fixtures, `a` and `b` can be zero-argument function symbols (or the terms `f(x)` and `h(x)`); this example does not require unsupported LiteralVariable patterns.

The fresh runner owns the graph from its empty state. It cannot certify a caller's pre-unioned graph, arbitrary raw unions or unsupported callback mutations. If the existing graph implementation makes an incorrect merge, reconstruction may fail and the checker must never accept an unexplained result.

## Frozen public kernel interfaces

All new kernel types below are in `de.regelsuche.egraph.proof`. Integer term/rule/step indexes are local to one builder or certificate, never e-class IDs.

### `ExtractionCertificate`

```java
public record ExtractionCertificate(
    int schemaVersion,
    List<Term> terms,
    List<RuleIdentity> rules,
    List<Step> steps,
    int inputTerm,
    int outputTerm,
    int conclusion) {
    public static final int SCHEMA_VERSION = 1;
    public enum Kind { NUMBER, VARIABLE, BINARY, FUNCTION }
    public record Term(Kind kind, String payload, List<Integer> children) {}
    public record RuleIdentity(String id, PatternExpr source, PatternExpr target) {}
    public sealed interface Step permits Reflexivity, Symmetry, Transitivity,
        Congruence, PatternInstance {}
    public record Reflexivity(int term) implements Step {}
    public record Symmetry(int premise) implements Step {}
    public record Transitivity(int first, int second) implements Step {}
    public record Congruence(int left, int right, List<Integer> childProofs)
        implements Step {}
    public record PatternInstance(int rule, Map<String, Integer> bindings,
        int before, int after) implements Step {}
}
```

Constructors defensively copy collections. The checker performs structural validation; constructing or decoding this data is never verification. Terms use canonical rational text, the exact `VariableExpr.name()`, a `BinaryOperator.name()`, or an exact function name respectively. NUMBER and VARIABLE have no children; BINARY has exactly two; FUNCTION preserves its ordered arity, including zero arguments. Term children must refer backward. Every step premise must refer to an earlier step. The builder interns structurally equal terms; the checker rejects duplicate structural terms, so exact term-index equality suffices after table validation. Neither operation may require unmetered recursive AST equality.

Rule identities bind the actual full source and target patterns. Schema v1 means exact recognition with an admitted equivalence-preserving base pattern rule; that semantics cannot be overridden by certificate metadata. The receiver supplies its own trusted rules. IDs select those rules; IDs or hashes alone do not authorize equality. Unrelated descriptive/licensing metadata in `RuleDescriptor` is not mathematical semantics.

### `ProofLimits`

```java
public record ProofLimits(
    int maxTerms, int maxProofSteps, int maxReferences,
    int maxCharacters, int maxRationalBits, int maxDepth,
    int maxExpandedNodes, long maxWork) {
    public static ProofLimits defaults();
}
```

All limits are nonnegative; a zero limit means the corresponding resource is unavailable. Defaults are `50_000`, `100_000`, `500_000`, `1_000_000`, `4096`, `256`, `100_000`, `5_000_000L` in that order. `maxTerms` bounds the semantic term table and separately the total traversed rule-pattern nodes; the number of rules must also fit it. `maxReferences` bounds retained child references, binding entries, proof premises and closure/forest edges. `maxCharacters` is cumulative retained label/canonical-number text, not merely a per-string limit. The existing core canonical rational envelope of 4096 characters also remains in force; a larger custom bit limit does not waive it.

The fresh runner additionally caps effective capture depth at256, even when a caller supplies a larger value, because the existing native pattern matcher/applier are recursive. The standalone iterative kernel may use a larger depth limit. Initial graph insertion uses the preflighted term DAG iteratively, once per distinct term.

The graph's `Config.maxNodes` and iteration limits remain separate. Capture/build work and independent checker work receive separate `ProofLimits` instances. These counts are not interchangeable with existing retained-operation, search or learning cost metrics.

### `ExtractionProofBuilder`

This producer owns bounded term interning, exact pattern instantiation, captured ground instances and finite congruence reconstruction. It never holds an `EGraph` reference.

```java
public final class ExtractionProofBuilder {
    public ExtractionProofBuilder(Collection<? extends RewriteRule> rules,
        ProofLimits limits);
    public int addTerm(ExtractionCertificate.Term term);
    public int addExpression(Expr expression);
    public Expr materialize(int term);
    public ExtractionCertificate.Term term(int term);
    public GroundInstance prepareInstance(String ruleId, Map<String, Integer> bindings);
    public void recordInstance(GroundInstance instance);
    public BuildResult build(int inputTerm, int outputTerm);
    public long workUsed();
    public void chargeWork(long units);

    public record GroundInstance(int rule, Map<String, Integer> bindings,
        int before, int after) {}
    public enum BuildStatus { BUILT, LIMIT_EXCEEDED, NO_DERIVATION }
    public record BuildResult(BuildStatus status,
        Optional<ExtractionCertificate> certificate, String detail, long workUsed) {}
    public static final class LimitExceeded extends RuntimeException {}
    public static final class UnsupportedRule extends IllegalArgumentException {}
}
```

`prepareInstance` creates the exact LHS/RHS term IDs but does not yet register an equality axiom. `recordInstance` records it only for the corresponding actual new native union. The builder validates even its own public method arguments. `addTerm`, `addExpression`, `materialize`, `prepareInstance` and `recordInstance` throw only the specific owned `LimitExceeded` for exhausted proof resources; malformed direct API arguments use `IllegalArgumentException`. `build` converts owned exhaustion into its typed result. It never returns `BUILT` without a certificate, or a certificate for another input/output. Building a candidate is still not checker acceptance.

`chargeWork` lets the fresh-graph adapter debit its own traversal and bounded rendering before operations against the same monotone capture/build allowance. It rejects negative units and throws the same owned `LimitExceeded`; it cannot reset or replenish the budget. This additive interface was agreed by both implementers before integration.

Registry preflight accepts only `rule.getClass() == PatternRewriteRule.class`, exact recognition, construction-preserving declaration and the native pattern forms Placeholder, LiteralNumber, Operation and Function. LiteralVariable, subclasses, arbitrary callbacks, other recognition profiles and non-preserving rules are unsupported in this first proof API. Reject duplicate IDs even when their definitions happen to be equal. Every target placeholder must occur in the source. Validate sizes/depth/character/scalar bounds before recursive `equals`, `hashCode`, `toString`, `instantiate` or any unbounded copy. Freeze source/target identities. No rule callback is executed by the checker or builder.

Typed rational preflight must also enforce immutable scalar ownership. Require both numerator and denominator to have runtime class exactly `BigInteger.class` before invoking their bitLength, canonical formatting, hashing or equality. BigInteger subclasses can override those operations or retain mutable state; they are unsupported proof inputs, including inside pattern literals. This follows the existing core `ExpressionIdentity` scalar boundary and protects the immutability of checker-token endpoints.

### `ExtractionProofChecker` and accepted evidence

```java
public final class ExtractionProofChecker {
    public static CheckResult check(Expr expectedInput, Expr expectedOutput,
        ExtractionCertificate certificate,
        Collection<? extends RewriteRule> trustedRules, ProofLimits limits);
    public enum Status { ACCEPTED, REJECTED, LIMIT_EXCEEDED }
    public static final class CheckResult {
        public Status status();
        public String detail();
        public long workUsed();
        public Optional<CheckedCertificate> checked();
    }
    public static final class CheckedCertificate {
        public ExtractionCertificate certificate();
        public Expr input();
        public Expr output();
        public long workUsed();
    }
}
```

`CheckResult` and `CheckedCertificate` constructors are private to the checker. Only an accepted check can create the checked token. The checker has no graph, `find`, saturation, extraction or congruence-search dependency. It computes step endpoints from checked premises:

- Reflexivity has identical endpoints.
- Symmetry reverses an earlier checked pair.
- Transitivity requires the exact same middle term.
- Congruence requires identical kind/payload/arity and one ordered child equality per position, with exact endpoint directions.
- PatternInstance selects the receiving caller's admitted rule, checks the full source/target identity, validates exactly the source placeholders and substitutes the explicit binding terms without matching. Both instantiated sides must be the claimed before/after terms.

The conclusion must join the exact separately supplied expected input and output, including exact rationals and scoped symbol identities. Forward references, cycles, unknown tags, malformed arities, missing/extra bindings, wrong rule identity, missing premises and mismatched endpoints are rejected. Resource exhaustion is distinct from rejection. The guarantee is equality relative to the caller-admitted rule axioms, not a theorem proof of those axioms themselves.

### `ProofOutcome`

```java
public final class ProofOutcome {
    public enum Status { COMPLETE, NOT_REQUESTED, UNSUPPORTED_RULE,
        LIMIT_EXCEEDED, REPLAY_REJECTED, EXTRACTION_UNAVAILABLE,
        UNSUPPORTED_OBJECTIVE }
    public static ProofOutcome complete(
        ExtractionProofChecker.CheckedCertificate checked,
        long captureWork, long buildWork);
    public static ProofOutcome unavailable(Status status, String detail,
        long captureWork, long buildWork, long replayWork);
    public static ProofOutcome notRequested();
    public Status status();
    public String detail();
    public long captureWork();
    public long buildWork();
    public long replayWork();
    public Optional<ExtractionProofChecker.CheckedCertificate> checked();
    public boolean complete();
}
```

`unavailable` rejects status COMPLETE. `complete` requires the actual checker-issued token. No public constructor can manufacture a complete outcome from an unverified certificate or boolean. `replayWork` for success comes from that token's `workUsed()`. Capture work is the builder's counter before reconstruction; build work is the subsequent increment, so their sum stays within the capture/build work budget.

## Producer integration and bounded behavior

### Fresh graph and anchor capture

Root integration owns a package-private `EGraphProofCapture` and a proof-aware fresh-graph construction path. A normal `new EGraph()` and every existing method retain their current behavior. Only proof mode records anchors; before each new e-class allocation it derives the term from the exact e-node symbol and current finite child anchors. The newly allocated ID owns that immutable term. `find(id)` selects the canonical survivor's already recorded anchor. No union or repair hook introduces a proof axiom.

Capture exhaustion is terminal for the run's proof availability. The capture adapter catches its specific `LimitExceeded`, records a stable reason and becomes a no-op. It must not throw into or interrupt congruence repair, or later resume and certify an incomplete premise set. Failed rule preparation similarly invalidates proof capture; the graph remains consistent. The implementation may stop new rule applications once capture is lost, but must finish pending rebuild work and report that policy explicitly. It must not claim a graph fixpoint due to a proof quota.

The native path captures one frozen binding map per actual union, prepares its ground instance, applies the unchanged native RHS, and records the equation at that union. It must not use the 256-witness cap as the authoritative premise limit. Diagnostic witness collection remains separate. Neither adding source terms to the live graph nor rerunning concrete matching is necessary.

In proof mode, bypass the legacy `nativeWitness` helper entirely: it calls recursive `graph.extract(binding, node -> 1)` before final extraction. This mode intentionally retains no local AST witnesses, reports `nativeWitnessExtractions == 0`, and counts actual applied unions omitted from that sample in `droppedWitnesses`. The bounded ground-instance store and accepted equality DAG carry the proof. Existing default-mode witness generation and counters remain unchanged. Optional diagnostic witnesses must not consume the proof quota or trigger an unbounded AST expansion.

### Extraction and input preflight

Proof mode preflights the source and every admitted pattern before any existing recursive insertion/matching/formatting helper. Preflight is iterative and memoized by identity where appropriate, but counts **expanded occurrences**, with saturating arithmetic, as well as unique nodes and depth. A shared DAG built by repeatedly using the same child twice is not a small expanded tree. `maxExpandedNodes` and `maxDepth` must reject it before recursive `addExpression` can revisit it exponentially.

Proof mode uses the same nonnegative additive extraction choices as the existing extractor, with bounded iterative reconstruction to the term DAG and output AST. It does not invoke an unbounded legacy `extract` or formatter after capture/depth exhaustion. It checks selected-tree cycles and expanded occurrence/depth limits before constructing the AST. Unavailable costs, an unextractable class, a cyclic selected reconstruction or an exceeded bound produce the explicit corresponding outcome. No reflexivity shortcut is allowed for an improved uncertified output. Arbitrary user cost-function CPU/allocation is outside owned proof-work bounds and must not be advertised as preemptible.

Bound formatting separately: a bounded number of AST occurrences can still repeat a long label into excessive output text. The existing `ExpressionFormatter.formatMeasured` debits emitted code units before appending; use that API with a proof-mode character/work allowance for diagnostic and search strings. A failed diagnostic format cannot cause an unbounded fallback. If a safe diagnostic Result cannot be assembled, leave it absent. Search mode must retain the original search state when its required output text cannot be rendered within the selected limits.

These bounds do not make the whole saturation call globally time/memory-bounded. The unchanged native matcher may enumerate and materialize combinatorial match sets before node/iteration guards stop the graph run. Capture, reconstruction, checking and bounded output construction are the owned phases covered here. No hard matcher isolation or global saturation bound is claimed.

The final certificate builder includes the exact input and chosen output and their subterms together with all recorded ground equations. It may initially retain all bounded terms (including finite anchors) rather than implement a speculative pruning pass. It constructs only explicit proof nodes whose references already exist. A straightforward metered fixed-point signature table plus a proof-bearing union forest is acceptable. Do not use a live `EGraph` as the congruence producer. Every explicit term scan, signature child, forest edge traversal and proof allocation is metered; a quota stops reconstruction with no complete certificate. The receiver's checker then independently accepts or rejects the candidate.

### Public entry point and result

```java
// Nested in EqualitySaturation:
public record ProofOptions(ProofLimits captureLimits, ProofLimits replayLimits) {
    public static ProofOptions defaults();
}
public record ProvedResult(Expr expression, Optional<Result> diagnosticResult,
    ProofOutcome proof) {}
public ProvedResult saturateWithProof(Expr input,
    ToIntFunction<ENode> costOfNode, ProofOptions options);
```

On COMPLETE, `expression` is the independently accepted extracted output. On any unavailable outcome it is the exact original input. `diagnosticResult` is present only when an actual saturation result was assembled within the owned bounds; it may expose an unproved diagnostic candidate and its honest graph statistics. Absence must not be replaced by invented zero-cost/fixpoint statistics.

Composite result invariants are mandatory. The fresh runner requires `checked.input() == input` and `checked.output() == expression` for its actual invocation. `ProvedResult`'s constructor rejects a COMPLETE outcome unless its `expression` is the exact output object retained by that checker token. The existing `Result` constructor likewise rejects COMPLETE provenance attached to another output object. These constant-time identity checks are sufficient because the checker itself proved exact value endpoints and the AST is immutable; they avoid unmetered recursive `Expr.equals`. A caller wishing to associate a separately reconstructed equal AST performs a new check for that AST. `ProvedResult` has no separate input component, so invocation-input binding is the runner's responsibility, not a claim about arbitrary caller-constructed wrappers. Existing constructor signatures and all legacy/no-proof behavior remain unchanged.

`SaturationProvenance` gains a `ProofOutcome proofOutcome` component/accessor, with its existing two- and three-argument constructors preserved through `ProofOutcome.notRequested()`. `hasExtractionProof()` delegates only to accepted complete evidence. Its local-witness list and accounting remain the diagnostic contract. The old API's outcome stays NOT_REQUESTED and its flag false.

### Explicit search adoption

Keep all existing `EqualitySaturationStrategy` constructors/default semantics. Add `EqualitySaturationStrategy(EqualitySaturation.Config, EqualitySaturation.ProofOptions)` as explicit proof-required mode and `lastProofOutcome()`.

That mode uses the fresh API. It returns the original search state whenever proof is unavailable, retaining the explicit outcome and any actual diagnostics. For a non-operator-count objective, it returns the original state with UNSUPPORTED_OBJECTIVE rather than silently returning an unproved optimized result; the legacy strategy retains its existing requested-objective BestFirst fallback. On success, expose the checked certificate through provenance, but retain the synthetic saturation edge's non-executable/non-construction-proven flags. A checked equality DAG is not one primitive rewrite step.

## Required acceptance and adverse cases

1. A child-only rewrite changes a parent; the detached certificate needs congruence. Exercise both current repair collision forms and hash-cons reuse after a prior union, without treating any repair merge as an axiom.
2. Repeated-placeholder matching across equivalent but textually different children, with an exact instantiated LHS that was never stored as one original tree. Tampering with its equality bridge must be rejected.
3. Extraction combines independently improved children into an output never inserted as a whole. Check both equality directions and transitive composition.
4. More than 256 essential ground rewrites produces an accepted certificate within configured proof limits. Capture, construction and replay exhaustion each return the input with distinct diagnostics and never a true proof flag.
5. Zero-cost live cycles, equal-cost alternatives, unavailable costs and an initially unextractable root preserve the established objective semantics or return an explicit bounded unavailable outcome. No second selection of a different output for proof convenience.
6. Deep/shared input and rule DAGs, exponential expanded trees, large function arity, large labels, huge rationals and deliberately small reference/work bounds fail before the relevant unbounded allocation or recursive helper. Exact-limit and one-short tests cover owned work/materialization boundaries.
7. A fresh checker with no graph access accepts copied/detached evidence and rejects changed expected input/output, duplicate structural terms, forward/cyclic references, wrong congruence child order/symbol/arity, incorrect transitive middle, altered rationals, swapped scoped variables, missing premises, duplicate IDs, substituted rule definitions and extra/missing bindings. Attaching a valid token for `a=b` to a public result whose output is `c` must fail immediately.
8. Unsupported callbacks, subclasses, non-exact recognition, literal-variable patterns, non-preserving rules and unbound target placeholders fail explicit preflight. Existing legacy execution still exercises those routes as before.
9. Proof-required search returns only a certified improved state; unavailable proof/objective keeps the original. Default strategy fallback and all existing synthetic-edge flags remain unchanged.
10. Final independent source review covers the induction, every semantic native union's capture site, no unbounded fallback after quota loss, producer/checker separation and the exact published source tree. Full canonical CI must qualify the final head before review-ready publication.

## Deliberate boundaries

No native AC, conditional caller context, arbitrary callback proof replay, persisted JSON/binary certificate codec, hard timeout for user callbacks, minimum-cost optimality proof, global saturation resource theorem or learning-default replacement is included. The result is a complete bounded equality certificate relative to admitted exact rules. Capture/build/checker work is reported separately; no speedup or default-audit saving is claimed.
