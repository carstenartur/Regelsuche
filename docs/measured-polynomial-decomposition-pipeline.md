# Measured specialized polynomial decomposition

Issue #748 adds an occurrence-aware, measured entry point to the retained
specialized operator:

```java
MeasuredPolynomialDecompositionPipeline.Result result =
    new PolynomialDecompositionSynthesisOperator().factorExpression(
        exactParsedRoot, selectedTreePosition, cumulativeAuthority);
```

The string-based API remains available. The measured entry preserves the rule
`hypothesis_polynomial_decomposition_synthesis`, the engine
`regelsuche.factorization.binary-quartic-2x2/v1`, integer coefficients,
structural AST atoms, and degree-four homogenization with the structural unit.
It selects verifier candidate zero and uses the existing renderer and application
key. It does not dispatch a rational or native univariate engine.

The input root is already parser-issued. Its initial parsing and the caller's
position enumeration belong to the caller's preparation ledger. The pipeline
projects that exact occurrence through `ExactParsedSubtermProjector`, including
the position text staleness guard, and inspects the projected parser provenance
directly. Position text never supplies coefficients or exponents.

| Work stage | Operation |
| --- | --- |
| `projection.*` | Existing exact occurrence navigation, range/literal validation, and source commitments |
| `exact-parsed-view.*` | Actual semantic visits, exact integer bindings and arithmetic, atom formatting, canonicalization, and existing homogenization |
| Original engine stages | Unchanged raw work returned by the binary-quartic engine |
| `verify.*` | Unchanged independent product-verification work |
| `render.*` | Existing candidate rendering, emitted text, and application-key construction |
| `transform.exact-reparse-*` | Exact parsing of the rendered replacement |
| `nested.replacement-*`, `nested.rewritten-*` | Shared `TreePosition` replacement, path replay, and exact original-source splice |

Every operation charge is admitted before the operation. Components use the
same caller-supplied `PolynomialWorkAuthority`; they never reset its cumulative
state. Before the synchronous opaque engine/verifier call, the pipeline admits
the authority's dispatch overhead and limits the request to the lesser of the
operator's existing engine/verifier ceiling and the authority's remaining opaque
allowance. It then settles the complete original report ledger before rendering.

`Result` has a private constructor. It retains terminal status/detail, projection,
root source hash, path, exact selected source, optional integer request and
verifier report, candidate index and original rendering, exact replacement parse,
and the shared structural replacement result. `sourceEvidenceHash()` is the
projector certificate, binding the original root, path, ranges, and literal
evidence. Nested output is spliced into the original exact source; untouched
siblings keep their AST identity and original source spelling.

`rawWork()` and `totalWork()` are the same invocation ledger. Earlier caller work
remains consumed in the authority but is not duplicated in that ledger. A late
refusal retains admitted work and already-issued request/report evidence.
`transformedExpression()`, `rewrittenRootSource()`, and the primitive expansion
authorize output only for `GENERATED`; rejected charges add no work.

Completed results issue six ordered proof steps: exact source evidence, integer
request, verifier-selected candidate, original rendering, exact reparse, and
specialized occurrence replacement. Their hashes and the cached canonical result
material are deterministic. The final step binds the entire pipeline certificate.
The exact reparse checks syntax and parser-issued numeric evidence; these steps
do not assert an additional general polynomial reconstruction of arbitrary AST
atoms. Product verification remains the existing integer verifier contract.

The focused tests cover finite positive and negative quartics, original candidate
and application-key parity, exact literals above binary64 precision, nested
occurrence identity and source preservation, partial-stage refusal, a one-unit
opaque allowance, spent cumulative authority, and deterministic/stale replay.
This foundation does not change search defaults, create a sealed qualification,
run the 600-row study, freeze a candidate, or claim a performance benefit.

```bash
mvn -o -pl regelsuche-core \
  -Dtest=MeasuredPolynomialDecompositionPipelineTest,PolynomialDecompositionSynthesisOperatorTest,PolynomialTheorySubsumptionClassifierTest,BinaryQuarticFactorizationEngineTest,FactorizationEngineContractTest,ExactParsedSubtermProjectorTest \
  test
```
