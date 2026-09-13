# Reference-independent candidate validation companion

This implements the validation tranche of #868 over an **already public,
archived** candidate matrix. It does not form candidates, reopen qualification,
change formation budgets, or provide fresh held-out evidence. Dossiers,
target-blind salience/ranking, downstream utility and the broader acceptance
criteria of #868 remain open.

## Frozen input authority

The compressed fixtures under
`regelsuche-discovery/src/test/resources/de/regelsuche/discovery/representation/reference-independent-v1/`
preserve exact uncompressed bytes from GitHub Actions run `34709221476`,
artifact `10303505557` (`repository-verification`). The archive SHA-256 is
`d736a8269253cd2d7e4d09e3c3230fa24f4f6c4b89d0205c54b6596c5fd4f0fc`.
`archive-provenance.json` records the byte lengths and byte/content hashes.

| Artifact | Content identity |
| --- | --- |
| Plan | `sha256:48e9067a5fcd9c91c310351d91ffa86ddeb04e40613c1a79dba4d8bf863e461a` |
| Candidate Freeze | `sha256:b4a8fd1dbc70a7fc5f59df61e9c6871f1be65c47df5441b017be7804e187090a` |
| Historical qualification, separate comparison only | `sha256:7317ba66e37e532ec945364efd4f1ee61b89cab7b39ffff32d6751a69bc274c7` |

The formation revision is `413d4a88cfd8a5a43d0bfb7655999cd9d49b5c64`.
The archive contains identical bytes in its original host-a, host-b and container
directories. This is a later existing CI reproduction, **not** the older
`82dae94f…` freeze documented in the September 2 salience baseline.

The input has 144 case/policy/checkpoint rows, 4,316 retained lineages and 2,219
row-local deduplicated candidate proposals. Each retained lineage in each row
receives exactly one outcome, including repeated expressions with different
paths. These counts are not independent mathematical tasks.

## Information and evidence boundaries

`ReferenceIndependentCandidateValidationRunner` consumes only canonical plan
and freeze bytes, the externally expected freeze hash, implementation revision
and separate validation limits. It uses the existing
`TargetFreeIntrinsicCandidateValidator` for every admitted equivalence-preserving
candidate. Historical qualification is not a runner input or an additional
validation hash dependency. Opaque commitments already contained in the v1
plan/freeze are retained through those artifacts' identities; their disclosed
contents are never opened by validation.

The `reference-independent-candidate-validation/v1` artifact binds:

- the exact source, row configuration, candidate batch/set/freeze receipt and
  original formation work ledger;
- each unchanged candidate, assumptions, primitive rule IDs and path expressions,
  aggregate equivalence-preserving declaration, input hash and primitive-path hash;
- the whole-expression occurrence `$`, exact source-string equality, complete
  oracle status/evidence/scope, terminal reason and validation work;
- a complete derived row/global work balance and canonical SHA-256 content root.

The historical freeze does not contain independently replayed per-step occurrence
receipts. The companion therefore states `FROZEN_LINEAGE_NOT_REPLAYED`; it does
not manufacture a primitive proof from retained path names.

The existing `SymPyOracleValidator` delegates to local deterministic numeric
samples and normalized quadratic coefficients. It does **not** invoke external
SymPy. The companion retains its legacy `SYMBOLICALLY_VERIFIED / AGREE` status,
but separately identifies `DETERMINISTIC_NUMERIC_SAMPLES` or
`NORMALIZED_QUADRATIC_COEFFICIENTS`, with `formalProofStatus=NOT_ESTABLISHED`.
Numeric agreement does not establish original-domain completeness. Conditional
non-agreement remains unresolved when the oracle cannot consume assumptions.
The legacy service's explicit `no equivalence evidence found` response is
retained as `UNAVAILABLE`, rather than turned into a refutation.

`ReferenceIndependentValidationHistoricalComparison` is a separate, subsequent
join. Its public API and CLI require an externally supplied expected historical
qualification content hash, in addition to checking the candidate freeze and
row bindings. The Gradle task pins the archived hash listed above. Relabeling
and rehashing the historical artifact cannot pass under that original pin.
Its output alone depends on historical labels. Tests explicitly authorize a
different comparison hash after changing every `referenceMatched` label and
demonstrate that scheduling and the validation content hash remain identical.

## Work and terminal outcomes

The default archived-matrix protocol admits at most 4,316 oracle invocations,
at most 8,192 source-plus-candidate Java string characters per invocation, and
a 5,000 ms deadline per invocation including worker startup when required.
These are separate validation limits; the original formation budgets and work
quanta are unchanged.

Each visited candidate costs one visit. Every admitted invocation is charged
before execution, including timeouts and technical failures. Input character
work counts the validator input's UTF-16 code units, not encoded transport bytes.
Oracle calls are logical API invocations, not internal arithmetic operations,
CPU time or worker process count. After a transport failure the bounded session
stays terminated; later admitted invocations explicitly return unavailable.
There is no hidden restart, retry or free failed invocation.

A fixed local JVM worker contains the existing oracle. Its heap and protocol
lines are bounded and accept LF or CRLF terminators. Deadline expiry forcibly
terminates that owned process;
cleanup cannot replace the original validation error. Real-process controls
cover hangs, exits and responses bound to the wrong request, without sleeps.

Unsupported, conditional-unresolved, refuted, timeout, technical-failure,
ineligible and budget-refused candidates remain in the output. Work refusal
never removes a frozen candidate. `verifyBindings` recomputes set/lineage equality,
input bindings, classifications and work admission in canonical order.
`verifyReplay` additionally re-executes every admitted oracle invocation in a
new worker: a self-consistent rehashed oracle claim is insufficient. This is
reproduction of the declared oracle, not a second independent mathematical method.

Legitimate timeouts or technical failures may differ in a fresh replay. The
original artifact and its charged work remain valid retained evidence under
`verifyBindings`; a differing replay leaves reproduction unconfirmed and fails
the reproduction gate. It neither overwrites the original artifact nor triggers
an automatic retry or replaces retained failure evidence with a positive claim.

## Running and verifying

From a clean checkout on Java 25:

```sh
./gradlew --no-daemon --no-configuration-cache --max-workers=2 \
  :regelsuche-discovery:verifyReferenceIndependentCandidateValidation
```

This runs the controls, executes the complete archived matrix, independently
replays the oracle calls, validates the JSON Schema and writes the separate
historical comparison. The task obtains its implementation revision from the
clean checkout or the existing CI authority. Reports live under
`regelsuche-discovery/build/reports/reference-independent-candidate-validation/<implementation-revision>/`.
Existing different report bytes are never overwritten.

The same real-process and complete-matrix Java controls run in the Maven reactor:

```sh
mvn -Pfull -pl regelsuche-discovery -am \
  '-Dtest=ReferenceIndependent*,TargetFreeIntrinsicCandidateValidatorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Local JUnit reports use an all-zero implementation revision when no CI authority
is supplied. They are test receipts, not evidence bound to a real source commit.
The public CLI also supports explicit archived inputs:

```text
ReferenceIndependentCandidateValidationRunner run
  <plan.json[.gz]> <freeze.json[.gz]> <expected-freeze-hash>
  <implementation-commit> <max-oracle-calls> <max-input-characters>
  <timeout-ms> <output.json>

ReferenceIndependentCandidateValidationRunner verify
  <plan.json[.gz]> <freeze.json[.gz]> <expected-freeze-hash> <validation.json>

ReferenceIndependentValidationHistoricalComparison
  <validation.json> <historical-qualification.json[.gz]>
  <expected-historical-qualification-hash> <output.json>
```

These commands use the discovery module runtime classpath. `verify` performs
binding verification and full oracle re-execution. The separate schema checker
uses the repository's existing pinned verification Python environment. It owns
only the exchange schema and content-root check; Java owns candidate and work
semantics and oracle replay.

## Observed bounded result and remaining work

The full local companion and replay retained 4,316 outcomes and 4,316 completed
oracle invocations, with 305,365 input characters. All received agreement under
the existing numeric-sample oracle. The subsequent historical join found 2,923
non-reference lineages whose historical oracle had not run; those now have
reference-independent oracle agreement. The original historical qualification
bytes and decisions remain unchanged.

This establishes completion of the bounded validation path, not completion of
#868. Unknown-candidate salience dossiers, executable downstream utility,
calibration-only ranking selection and untouched-TEST recall/false-positive
evaluation remain open. Expert relevance (#389) and external novelty (#391)
remain independent decisions.

The original archive's two-host/container identity is provenance for the input
freeze. It is not a new two-clean-checkout/pinned-container reproduction receipt
for this companion implementation. That broader reproduction requirement remains
distinct from local Maven/Gradle execution and oracle replay.
