# General algebra for Java computation plans

The source computation is the optimization problem. No target formula or named application problem is required by `ComputationOptimizer.optimize`.

The default SDK candidate portfolio now also feeds homogeneous typed arithmetic islands through the existing core `AstRewriteTransformationEngine.defaultRules()` and native `AstRewriteTransport`. It lowers proposed mathematical representations back to the same Java numeric domain and sends them to the existing `JointPlanSearch` / `TypedSourceOnlySearch` frontier. There is no second search engine. Existing local and modular rules remain additional mathematical knowledge, not a source-pattern selector.

## Safety boundary

The core rule's mathematical equivalence flag is not a Java proof. Every admitted move and the final incumbent still pass the independent `SemanticChecker`. Integer division, finite-width overflow, casts, floating-point rounding and original operation traces retain their existing contracts. Conditional rules are not allowed to invent missing assumptions. Casts and operations outside a homogeneous arithmetic island remain opaque operands.

The new bridge supports representable `int`, `long` and `BigInteger` arithmetic and bounded lowering of nonnegative integral powers. Existing floating-point rules are unchanged; general rational reassociation is not enabled for `float` or `double`. Unrepresented operators and unproved conditions are rejected, rather than replaced by an expected test answer.

## Bounded exploration

General algebra may first increase expression size before discovering a cheaper equivalent form. It gets a bounded candidate share after the existing domain proposals. Native work, cancellation and scope cleanup are accounted for. The optimizer reserves half the remaining work allowance for independent final verification and preparation. A proved improving incumbent can be returned when exploration stops, but only inside the whole request budget. With no improvement, an exploration limit is not reported as exhausted equivalence space. `IMPROVEMENT_FOUND` is not an optimality guarantee.

## Verified explanations and their budget

`ComputationExplanations.describe(request, candidate, token)` independently reverifies the supplied candidate before constructing presentation data. Verification and presentation share one `VerificationWork` allowance and one deadline established at the start of this call. Finishing verification does not replenish the request's work or time budget.

`Explanation.presentationWork()` reports only the presentation-phase delta. It is not the total cost of `describe`; a `BudgetExceeded` result reports the shared charged work. A failed proof, stale evidence, cancellation or exhausted allowance returns no explanation. Consumers must check the result rather than displaying a partially prepared graph as verified.

The views contain the verified original/replacement operations, shared intermediates, assumptions and runtime obligations. A Java adapter may render these as source comments, preview details or a report, but presentation must not invent premises, timing improvements or cryptographic safety claims. Operation counts are not measurements of generated machine-code performance.

## Consumer integration

A source adapter translates Java bindings, conversions, control flow and original evaluation order; mathematical relations must be obtained by the mathematics engine rather than by recognizing a known application example. Source helpers may be expanded by their actual semantics without assigning mathematical meaning to a method name. Unsupported effects and missing contracts remain explicit boundaries.

A consumer must build and qualify the exact SDK revision it embeds. Merging a dependency PR into a feature branch is not a main integration, and old reproducibility receipts cannot be relabelled for a different JAR. Generated Java does not depend on the SDK at runtime.

## Regression evidence

The target-free tests include composed expressions, generated operand trees, 24 generated arithmetic programs and negative controls for integer division and floating-point reassociation. An ablation disables the core inventory and confirms that its candidate stream disappears. A separate regression exercises budget-limited exploration followed by independent verification. Generated programs are test coverage, not evidence of optimizing every unseen program.

Explanation-budget regressions cover separately affordable phases whose sum exceeds the allowance, the exact combined work boundary and cancellation between verification and presentation. These checks are additional to the original numerical and evidence-binding checks.

The companion Sandbox correction removes the constructor-specific mathematical recognizers rather than moving them into this repository. Scalar computations inside ordinary loop bodies use the normal source adapter; deriving arbitrary loop invariants, method contracts or constructor-field relations is not implemented by this change. The historical Bouncy Castle constructor must remain diagnosed until those facts are genuinely obtained. No performance measurement, upstream-ready constructor optimization, or new screenshot qualification is claimed here.
