# Observed work for exact polynomial preparation

`RulePreparationPlanner.planObserved` and `verifyObserved` add the measurement
contract `regelsuche.exact-polynomial-preparation-work/v1`. They execute the
existing dense integer-coefficient quotient algorithm. The old `plan`, `verify`,
solver-attempt `WorkLedger`, planner/application/certificate identities and
certificate bytes keep their historical meaning.

## Authority and retained outcomes

The caller supplies one `PolynomialWorkAuthority` for the whole run. Its
`consume` method must admit each charge atomically, or throw `LimitReached`
before the operation; it must never reset or refund earlier consumption. The
planner's private observer forwards every charge immediately and records only
admitted charges. It does not reserve a separate budget or accept a caller's
claimed work ledger.

```java
var observed = planner.planObserved(expression, runPolynomialAuthority);
if (observed.completed() && observed.attempt().orElseThrow().application().isPresent()) {
    var application = observed.attempt().orElseThrow().application().orElseThrow();
    var checked = planner.verifyObserved(application, runPolynomialAuthority);
    if (checked.completed() && checked.verified()) {
        // A caller may now attempt its separately accounted principal replay.
    }
}
```

Both result classes have private constructors. They retain the original input,
the contract ID, the exact per-call delta, outcome, stable detail code, original
failure class and any refused charge. A refused charge is not included in the
consumed delta. Throwable messages are not retained in this evidence.

`MeasuredAttempt.attempt()` is absent after authority exhaustion or technical
failure, so a partial application cannot authorize a candidate. `completed()`
means the measured call returned normally, including ordinary negative planner
outcomes. In particular, the old solver-attempt budget can return its ordinary
`BUDGET_INCONCLUSIVE` status in a completed observation. `MeasuredVerification`
can verify only on a completed call; invalid applications retain the work of
the independent rejection.

The public authority interface does not expose a global used/limit snapshot.
The run owner retains that snapshot from its own authority, together with all
privately issued call deltas and the input occurrence/run identity. It must
obtain observations from the current execution, not accept serialized metrics
as proof of execution. An unbounded authority is an explicit admission policy,
not evidence of a finite budget.

## Units and admission boundary

Stage keys have `preparation.plan.` or `preparation.verify.` prefixes. The
planner's independent internal verification is nested under
`preparation.plan.internal-verification.` and uses the same authority.

| Stage family | Actually observed work |
| --- | --- |
| `planner.dispatches`, `verification.dispatches`, `solver.invocations` | Entries into the respective algorithm phases |
| `polynomial.ast-node-visits` | Nodes inspected by semantic extraction, including power exponents and rejected fragments |
| `integer.*`, `scalar.integer-predicate` | Each invoked dense-algorithm BigInteger operation or integer predicate, including failed or non-exact division |
| `*.operand-bits` | Sum of inspected signed operand widths, `max(1, bitLength() + 1)`, for that invocation |
| `measurement.integer-width-inspections` | Each width inspection, itself admitted before calling `bitLength()` |
| `polynomial.*-slots` | Actual dense coefficient-array allocation, fill, literal and copy lengths, before those operations |
| `render.*` | Scalar factories and AST node construction invoked by quotient/prepared-expression rendering |
| `verification.ast-node-pairs`, `text.*` | Explicit AST and field comparisons, including each pair of UTF-16 code units actually compared |
| `format.*` | Visited formatter nodes, numeric projection calls, scanned/emitted/materialized code units |
| `obligation.*`, `assumption.*`, `certificate.*-code-units` | Text lengths admitted before the corresponding concatenation, projection or copy |
| `certificate.sha256-invocations`, `certificate.sha256-input-bytes` | Digest invocation and exact encoded UTF-8 input length, admitted together before the digest |

The operation and its operand-bit charge are admitted as one ledger. Width
inspection has already happened if that later charge is refused and remains in
the prefix. Numeric operation counts are never inferred from a quotient,
certificate or result string. SHA-256 work is not reconstructed by issuing an
extra certificate: the two real digests in a successful plan are observed at
their execution sites. A fresh independent verification adds its own digest.

This is a versioned operation model, not elapsed time, machine instructions or
a bit-complexity estimate. ExactRational factories and BigDecimal/string
conversion calls are counted at their public operation boundary with available
integer operand widths; their internal gcd/division, JDK implementation and
allocation details are not separately enumerated. Ordinary Java control flow,
constant access, record validation, collection bookkeeping and the observer's
own map/allocation overhead are outside these units. Summing heterogeneous
units implements the caller's declared budget policy; it does not establish
comparable physical cost across different algorithms or contracts.

## Coverage and remaining gaps

A successful plan includes both extraction/division passes, both quotient
renderings, prepared AST construction, residual/assumption text, all bound-field
verification, and both certificate digests. Negative quotient and unsupported
fragment paths retain the operations they actually execute. Runtime exceptions
and stack exhaustion produce a technical-failure observation with the admitted
prefix; VM-fatal errors are not converted into successful or negative results.

This boundary starts with an existing immutable AST. Source parsing,
source-occurrence/provenance projection, surrounding-tree lifting, native
principal replay, bridge search/replay, foreign solvers and a runner's own
evidence hash require their own accounting. The existing legacy APIs do not
acquire these new measurement claims implicitly.

In particular, the older `PatternTargetedLocalBridgeSearch.verify` still calls
normalization/parser/formatter, `AstRewriteTransformationEngine.transform`,
terminal matching and principal replay without this authority. Its
`structuralFingerprint`, `stateKey` and `certificateHash` still call the local
unmetered SHA-256 helper. `UnifiedRulePreparationCoordinator.verify` and
`SharedUnifiedRulePreparationCoordinator.verify` recompute evaluations and
compare their records; their historical logical work fields are not complete
interior-operation observations. `SharedPreparedPrincipalReplay` also invokes
the native engine and its own certificate hash without this authority. These
paths are unchanged in this slice.

Consequently this measurement can supply real exact-preparation work to the
four-profile runner, including failed attempts. It cannot by itself open a
matched-total-work or study-qualification gate while bridge, principal and
SymPy interior work remains unavailable. No frozen study, public run, threshold
or historical qualification is changed.

## Controls

The focused Maven controls exercise the original planner and dense polynomial
tests, parser/formatter regression cases, actual SHA-256 provider calls, actual
BigInteger division calls, admission refusal before those calls, cumulative
plan/verification budgets, invalid certificates and technical-failure prefixes.
Nine canonical outcomes were captured before the implementation; their old
status/work/detail/residual/application material hashes and the observed
successful application values remain identical.

Independent review additionally probes the actual `BigInteger.bitLength` and
integer decimal-conversion calls, and an actual SHA-256 provider failure. A
refusal performs no forbidden call; a provider failure retains its admitted
UTF-8/digest prefix across repeated calls. The focused set passes 51 tests in
eight suites. Separately compiled parent planner/polynomial/formatter classes
also reproduce the complete attempt/application/certificate material of all
nine historical cases byte for byte. These controls validate the declared
operation model, without adding a total-work or study-qualification claim.

```bash
mvn -o -pl regelsuche-core -am \
  -Dtest=MeasuredPreparationReviewTest,MeasuredRulePreparationPlannerTest,MeasuredUnivariatePolynomialTest,UnivariatePolynomialTest,RulePreparationPlannerTest,RulePreparationTransformationEngineTest,ExactExpressionFormatterTest,ExpressionParserExactLiteralTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
