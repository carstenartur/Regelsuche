# Java computation optimization SDK

Released binaries are available from Maven Central as
`io.github.carstenartur:regelsuche-optimization-sdk:0.5.3`.
Start with the [current SDK distribution guide](../docs/java-sdk-quickstart.md#maven-central-ab-053).
The older 0.5.2 ZIPs and their qualification records remain documented in the
[archive instructions](../docs/java-sdk-quickstart.md#1-archivierter-weg-das-sdk-repository-052).

The following API reference describes this `0.5.4-SNAPSHOT` source checkout.
The [availability matrix](../docs/mathematical-capabilities.md) distinguishes
source APIs, released 0.5.3 binaries, Workbench demonstrations and consumer
integration. New source methods do not retroactively become part of a release.

## Exact finite certificates in current source

`power.QuadraticRootSdk.search`, `verify` and `evaluate` expose bounded
source-program search for roots in a quadratic extension over a certified
prime field. The core derives shared powers, searches signed coordinate
permutations and independently replays the original and candidate programs.
`EXACT_PAIR` and `UP_TO_JOINT_SIGN` are distinct raw observations.
`verifyNormalized(expectedRoot, expectedNormalizer, certificate, budget, cancellation)`
also verifies exact equality after an independently specified parity selector
chooses a common orientation for the same target bit. The consumer binds that
selector, target and conditional joint negation to the observed source. The SDK delegates
budgets and cancellation; a Java consumer must establish the input domain,
effects and emitted-code equivalence. See [field program contracts and exact
prime evidence](../docs/research/quadratic-root-programs.md).

`combinatorics.FiniteConstraintSdk.count` and `verify` provide exact weighted
Boolean model counts, conditional counts through source-bound fixed variables,
an elimination trace and a positive-weight witness. The verifier independently
replays each local sum. See [factor tables and counting limits](../docs/research/finite-constraint-counting.md).

`countCnf` accepts signed one-based literals and zero-based fixed-variable
keys. Its compact OR chains have a unique auxiliary extension, and
`verifyCnf` checks the original clauses independently. `countIndexed` uses
bounded factor incidence for elimination. `countWithPolicy` returns the
selected provider and all paid attempts; its overload accepts an explicit
fixed strategy. Failed probes and fallback share the query budget. The
[frozen lifecycle study](../research/benchmarks/constraint-lifecycle-v1/README.md)
reports acquisition and failed training trials and finds no learned advantage.

`equivalence.RationalFunctionSdk.simplify` returns a bounded rational
candidate with the original expression's nonzero requirements. Missing
assumptions yield `REQUIRES_ASSUMPTIONS`, without a verified certificate.
`verify` independently binds the source, equality and original domain;
receipt assumptions cannot authorize new holes. Zero powers require a
nonzero base. See [domain contract and independent comparison](../research/benchmarks/rational-functions-v1/README.md).

`combinatorics.EnergyGameSdk.solve` and `verify` expose bounded cyclic
Min/Max credit synthesis and local invariant checking. Every Min edge and the
chosen Max edge must preserve the certified nonnegative credits inside the
region. See [energy-game contracts and example](../docs/research/energy-game-certificates.md).
Resource exhaustion in either API carries no mathematical result. Both are
source APIs introduced after the published 0.5.3 release.

`positivity.SosSynthesisSdk.search` and `verify` expose bounded rational Gram
synthesis with polynomial equality/inequality premises and polynomial potential
synthesis for a supplied simultaneous transition. The independent checks bind
the full coefficient identity and every domain assumption. A termination claim
requires the caller's explicit transition/domain contract. See [SOS and potentials](../docs/research/sos-synthesis.md).

`algebra.BilinearProgramSdk.search`, `verify` and `evaluate` find shared products
for a multioutput rational tensor from finite input-form dictionaries. The
verifier checks every tensor coefficient; reported costs include additions,
constant factors and temporary slots. See [search limits and executable example](../docs/research/bilinear-program-search.md).

The source APIs also expose a rational determinant-pencil lower bound for
2×2×2 tensors, checked input-basis transport, and costed integer-plan reuse.
The complex multiplication example now has matching rational lower and upper
bounds of three. Transfer checks both the original and target tensors;
the [basis-change study](../docs/research/bilinear-input-basis-study.md) reports
the stronger fixed classical control and all acquisition costs.

`noncommutative.RationalFormulaSdk.realize` constructs an affine pencil for a
rational formula. `verify` binds it to every inverse occurrence. `evaluate`
uses the supplied rational square matrices and reports the exact value or
the singular inverse that makes this assignment undefined. These operations
take `IterationLimits` and a cancellation token.
[Mathematical contract](../docs/research/rational-formula-realization.md) · [Java 25 example](../examples/external-consumers/checked-math-java25/src/main/java/example/RationalMatrixFormula.java)

`polynomial.DynamicAlgebraSdk.evaluate` splits a square-free quotient over a
prime field when an inverse or zero test encounters a zero divisor. Each
terminal factor carries a value or an explicit undefined result. `verify`
checks the complete coprime factor cover, values and decisions against the
original expression. Both methods accept an `OptimizationBudget` and a
cancellation token. [Mathematical contract](../docs/research/dynamic-algebra-evaluation.md) · [Java 25 example](../examples/external-consumers/checked-math-java25/src/main/java/example/DynamicQuotient.java)

These four APIs require the `0.5.4-SNAPSHOT` source checkout and a Java 25
build. Release 0.5.3 predates them. Their examples run as Java programs.

`positivity.PolynomialPositivitySdk` exposes rational polynomial `bounds` and
`verifyBounds`, subdivision `prove` and `verify`, exact `checkMatrix`, and
`verifyPolynomial` for a supplied Gram identity. A PSD matrix proves a
polynomial inequality only after the exact reconstruction check. Negative
Bernstein coefficients can be inconclusive; they do not establish a negative
polynomial. See [bounds, proof scope and executable tests](../docs/research/polynomial-positivity.md).

`modular.HunterCoverageSdk.bounds` and `verify` use exact singleton and tree-edge
intersection counts. A positive certified lower bound establishes a free
point in the specified finite progression. `proof.FiniteProofDagSdk.verify`
combines checked count leaves of the same expected source and allows an
existence conclusion only from a positive bound. `proof.FiniteProofDagJson`
transports the closed, versioned node vocabulary; decoding is not verification.
See [the finite proof contract and example](../docs/research/hunter-finite-proofs.md).

The existing `linalg.ModularKernelSdk`, `noncommutative.NoncommutativeIdentitySdk`
and `modular.MontgomeryScheduleSdk` are also source APIs introduced after 0.5.3.
Their individual contracts cover [kernel reconstruction](../docs/research/modular-kernel-profile-recovery.md),
[bounded noncommutative identity checks](../docs/research/noncommutative-identity.md)
and [finite Montgomery schedules](../docs/research/montgomery-schedules.md).
All these SDK adapters forward typed inputs, budgets and cancellation to the
mathematical core. The [bilinear proposal adapter](../docs/research/bilinear-core-proposals.md)
likewise retains Java type eligibility while the core owns algebraic candidate construction.

Finite reductions have a separate scoped API alongside scalar computations. See
[FINITE_SERIES.md](FINITE_SERIES.md) for constant and runtime-bound polynomial sums,
nested/periodic/piecewise composition, exact rational telescoping, affine and coupled
linear recurrences, geometric series, products, pair and filtered-window reductions,
ordered tuples and the shared kernel's inversion plans, with independent checks and
explicit limits. The standalone Java25 consumer exercises runtime polynomial and affine plans,
including rejection of changed evidence.

The current `0.5.4-SNAPSHOT` source also exposes
`de.regelsuche.sdk.optimization.modular.CongruenceCoverageSdk` for exact
coverage of finite arithmetic progressions by congruences with activation
thresholds. The mathematics and certificate replay live in
`regelsuche-math-algorithms`; the SDK forwards the request, work/state/time
limits and cancellation token. See [the mathematical contract and
reproduction](../docs/research/congruence-coverage.md), including the
trillion-slot scheduling example and the limits of finite certificates.

`CongruenceCoverageSdk.bounds` and `verifyBounds` provide a separate count-bound
mode. Complete Bonferroni intersection layers certify lower and upper bounds
when constructing the exact union would exceed the term budget. A positive
lower bound proves existence in the supplied finite progression; it does not
locate the first free value. The source request and requested order are part
of the certificate.

`de.regelsuche.sdk.optimization.combinatorics.BipartiteMatchingSdk.search` and
`verify` expose maximum bipartite matching and Hall-deficiency certificates.
CORE constructs a matching and a left-side witness; an independent verifier
derives its full neighborhood and an equal-size vertex cover. Applications
using the deficit as a survivor bound must establish that all actual blockers
are represented and have capacity one. See [the graph contract, examples and
verification](../docs/research/bipartite-matching.md).

`de.regelsuche.sdk.optimization.combinatorics.ConflictMatchingSdk.search` and
`verify` add independently justified pairs of incompatible edges. The core
uses its exact LP producer and then directly replays a source-bound weighted
cover. The result is a fractional upper bound and its safe integer consequence,
not an assertion of integral optimality. Budgets include all search and replay
phases; exhausted calls publish no bound. See [the proof, canonical edge
indices, controls and limits](../docs/research/conflict-matching.md).

The `0.5.4-SNAPSHOT` source also exposes
`de.regelsuche.sdk.optimization.modular.PolynomialCongruenceSdk.solve` and
`verify`. Pass a core `PolynomialCongruence.Request` with signed
`BigInteger` coefficients in ascending exponent order, a positive `int`
modulus and a signed `BigInteger` target residue. A `COMPLETE` result contains
the sorted preimage, which can be empty. An `UNAVAILABLE` result carries a
reason and no solution. The core compares the exact source and replays the
finite scan during verification. `maximumStates` bounds the coefficient and
root counts individually. The facade also forwards `maximumWork`,
`timeoutMillis` and `CancellationToken`. See [the mathematical contract and
reproduction](../docs/research/polynomial-congruences.md).

The Java 25 headless facade `de.regelsuche.sdk.optimization.ComputationOptimizer`
uses the existing `JointPlanSearch`, `JointComputationPlan` and
`PreparedJointComputation`. It does not contain an Eclipse adapter or a second
search engine. Math owns the independent affine modular algebra; the SDK owns
the Search/Math wiring. Experiments consume that wiring.

An `OptimizationRequest` supplies a typed plan, the ordered source operation
occurrences, selected numeric kinds, assumptions with provenance, policy,
goal, work/state/candidate/time budgets and a cancellation token. Source
adapters must preserve duplicate, eliminated and dead operations in their
trace. `SourceEvaluationTrace.fromPlan` is for synthetic expression examples.
Unproved, unsupported, cancelled, exhausted and improved outcomes are typed.

## Numeric contracts

* `PRESERVE_JAVA`: int/long polynomial identities use arithmetic modulo the
  executed width; bounded bitwise proofs keep promotions and casts distinct.
  Floating point uses conservative IEEE identities and exact CSE. No arbitrary
  real-algebra reassociation is certified under this profile.
* `GUARDED_FALLBACK`: range/finite/bit comparison obligations cover original
  and replacement traces; a failed gate selects the original computation.
* `CHECKED_THROW`: explicit policy retains the original and replacement range
  checks, rejects nonfinite floating results and compares floating output bits.
  Tiny inexact underflow is currently unsupported and is rejected explicitly.
* BigInteger inputs require exact nonnull value semantics and proven absolute
  bit-length bounds. The adapter establishes or guards these facts; they are
  not inferred from the Java declared type. Every BigInteger intermediate is
  proved below the conservative 1,000,000-bit fragment bound, preserving
  supported-range ArithmeticExceptions. Resource exhaustion and identity
  observations are outside this explicit value contract. Integer scalar power
  and shift parameters retain Java's int signature and bounded domain.
* Modular powers require positive moduli and nonnegative affine exponents.
  Returning a bare base requires its normalization premise. Equality modulo
  a modulus never licenses returning an unnormalized result.

Preserve-mode floating point needs an explicit no-NaN-payload-observation
contract. Checked and guarded finite paths do not return a transformed NaN;
the guarded fallback retains the original computation. Samples can refute,
never prove. Heavy BigInteger operations are not executed for constant
folding or counterexample diagnostics; cheap diagnostic intermediates are
limited to 4096 bits. Budgets and cancellation are charged during proof,
generation and final preparation.
Final cost assessment reuses immutable costs from source validation and the
selected prepared search result within the same request. The independent final
proof and the trusted Candidate constructor's preparation remain fully charged.
Preparation-limit `Unsupported` outcomes during initial source validation report
a conservative `work` upper bound, including earlier trace checks. Unused
request allowance can fund smaller requests. Other or legacy unsupported outcomes
use `-1`; consumers must still reserve their allocation and any unreported bound.
Witness capture retains the search's original immutable source envelope instead
of rebuilding it. External replay still reconstructs and verifies the source.
For `BudgetExceeded`, `hasCompleteWorkAccounting()` certifies when the reported
counter can replace the request reservation. A completed bounded search may use
less than the outer allowance; partial checker stops still require the allocation
or reported overshoot, whichever is greater. Consumers must not infer completeness
from a small counter alone.

`OptimizationResult.Unsupported` now includes `work`, and `JointPlanSearch.Result`
retains `sourceExpression` for producer-side capture. Their previous constructors
and accessors remain available; source callers using record deconstruction must
adapt to the additional components. Checker revision `java-numeric-independent/v11`
rejects v10 evidence; regenerate and independently verify receipts on upgrade.

`verify` checks a proposed plan independently; `reverify` additionally binds
all versioned evidence and runtime obligations to the current request.
Candidate construction rebuilds its prepared plan with the trusted Java
backend, preventing substitution of a behavior-changing backend. Reference
checked execution enforces all numerical assumptions before using them.

Costs are declared estimates, not measurements. They include every source
operation occurrence and active checking/fallback costs. READABILITY can
return a candidate that is explicitly not an estimated runtime improvement.
External receiver guards must be charged by the source adapter before it
claims runtime improvement. Unsupported effects, aliasing, control flow,
dispatch, exceptions and observations are rejected at the source boundary.

## Typed word relations and CAS boundary

The Java bitvector checker also projects constant shifts and primitive integral
casts to individual source bits. Its variables are `(typed word, bit position)`,
so shifting a word never identifies different bits. It preserves Java's masked
shift distances, signed widening and `char` zero extension. Each output bit is
checked universally over at most eight independent source bits; unsupported
width or budget cases fail closed. The established opaque-word proof remains
the first, cheaper path. All preparation and truth-table work shares the caller's
budget, deadline and cancellation token.

The ordinary candidate generator can reconstruct a word from complementary
high/low projections, including narrowed low words, for example
`((x >>> 32) << 32) | ((long)(int)x & 0xffffffffL) -> x`.
This is a generic typed proposal, independently proved and costed. It is not a
Nat256-specific rule or a claim that an existing carry chain becomes faster.

`JointComputationPlan` / `OptimizationRequest` remain the typed computation
contract for future configurable CAS providers. Existing `SolverIr` backends
represent proof obligations, not Java computation graphs: a provider must use a
lossless, capability-checked projection of supported mathematical subgraphs.
SymPy already has limited adapters elsewhere in the repository; this change does
not start a SymPy process or add a competing backend interface. CAS proposals
must reenter the existing independent Java verifier. Source-adapter memory
events, local commits and exception ordering remain separate obligations.
See [the architecture decision](../docs/superpowers/specs/2026-10-26-typed-cas-computation-design.md).

Computation graphs also recover exact structural sharing after a cold codec
roundtrip. The common plan resolver interns shallow node keys and child IDs;
it never hashes a recursively expanded expression. All decoded occurrences and
key work remain charged. The same eight-word carry plan keeps its 87 prepared
nodes after transport instead of failing preparation on 1,753 duplicated tree
occurrences. Node, depth and expanded-transport limits are unchanged. Consumers
can use `resolveOutputs(LongConsumer)` for synchronous budget/cancellation and
`MAX_RESOLUTION_WORK` for conservative reservations on legacy failure paths.

## Shared integral domains

`IntegralDomainAnalysis.analyze(plan, sourceTrace)` reports an exact local input
interval and affine outputs for the supported one-input primitive integral
fragment. `compare(source, sourceTrace, candidate)` independently checks equal
named outputs and that the candidate's no-overflow domain includes the original
domain. Both original and candidate ranges are returned separately. Source trace
occurrences retain dead intermediates; nested operations and narrowing casts are
also constrained, even when they are absent from a supplied trace.

For example, the `int` expression `(2*x)/2` has the exact no-overflow interval
`[-1073741824, 1073741823]`, while `x` spans the full `int` interval. This does
not prove Java wraparound equivalence outside the original interval. Adapters
must explicitly authorize changed overflow behavior; these results do not add
caller assumptions. Nonlinear products, bit operations, inexact division and
multiple independent inputs return `Unsupported`, as do exhausted work bounds
and cancellation. Overloads share a deterministic work limit across comparison.
Each graph resolution charges actual traversal/interning work through the core's
structurally bounded resolver; the default allowance is 100,000. Declared and
evaluated trace kinds both constrain the reported interval.
The migrated affine calculation retains its original EPL-2.0 notice.

## Reproducible local distribution

The BOM aligns the Maven and Gradle version of
`io.github.carstenartur:regelsuche-optimization-sdk` starting with 0.5.3. The pinned standalone distribution
uses the checkout's base version in `<version>-issue1657-<commit12>` and the
classifier `all`. It includes the dependency closure, licenses, sources,
Javadoc, an example consumer and SHA-256 provenance.

From a clean committed checkout, build with Java 25:

```sh
SDK_SOURCE_EPOCH=$(git show -s --format=%ct HEAD)
SDK_REPOSITORY=$(mktemp -d)
mvn -B -Psdk-release -pl regelsuche-optimization-sdk -am clean install \
  -Dproject.build.outputTimestamp="$SDK_SOURCE_EPOCH" -Dmaven.repo.local="$SDK_REPOSITORY"
mvn -B -pl regelsuche-optimization-sdk dependency:copy-dependencies \
  -DincludeScope=runtime -DexcludeGroupIds=io.github.carstenartur \
  -Dmaven.repo.local="$SDK_REPOSITORY" -DoutputDirectory=/absolute/external-runtime
mvn -B -pl maven-build-contract -am \
  -Dtest=MavenOptimizationSdkDistributionTest#packageConfiguredDistribution \
  -Dregelsuche.sdk.packageOptimization=true \
  -Dregelsuche.sdk.optimizationRepository="$SDK_REPOSITORY" \
  -Dregelsuche.sdk.optimizationDependencies=/absolute/external-runtime \
  -Dregelsuche.sdk.optimizationOutput=/absolute/distribution test
```

The commit timestamp fixes Maven input-JAR timestamps as well as the deterministic
outer archive. Use the same Java/Maven versions and flags for both clean builds;
the provenance includes raw input-JAR hashes, not only their class entries.
The packager refuses dirty sources, conflicting class bytes and forbidden
application dependencies. It merges service entries and keeps dependency
notices. Repeat the same build and packaging command, then compare every
artifact hash. The standalone packager and isolated consumer checks run through
Maven/JUnit. The normal API, publication, coverage and `ciCheck` authorities
include this module. See the [current qualification and integration status](../docs/superpowers/plans/2026-10-26-typed-cas-computation.md#current-status-2026-10-27).
The [October 4 reconstruction report](../docs/java-optimization-sdk-qualification.md)
is historical evidence for its recorded revision, not the current distribution.

## Standalone counted-sum CLI

Set `OPTIMIZATION_SDK_JAR` to the qualified `-all.jar` from the standalone
distribution above and invoke the CLI with Java 25.

```sh
printf '%s\n' '{"version":1,"kind":"counted-sum","bits":32,"overflow":"wrap","start":0,"stride":1,"initial":0,"term":{"op":"index"}}' |
  java -cp "$OPTIMIZATION_SDK_JAR" de.regelsuche.sdk.optimization.iteration.PolynomialSumCli
```

On success the CLI writes one JSON object to stdout.

```json
{"version":1,"request":{"version":1,"kind":"counted-sum","bits":32,"overflow":"wrap","start":0,"stride":1,"initial":0,"term":{"op":"index"}},"initial":0,"coefficients":[0,1]}
```

The CLI takes no arguments and reads one UTF-8 JSON object through EOF, up to
4,096 bytes including whitespace. All shown request fields are required. Extra
or duplicate fields, trailing JSON values, and noninteger numeric tokens are
rejected. Every term uses the arithmetic selected by `bits` and `overflow`.

| `bits` | `overflow` | Meaning |
| --- | --- | --- |
| 32 or 64 | `wrap` | Signed two's-complement arithmetic at the selected width |
| 32 or 64 | `checked` | Exact identity for a signed source whose operations are checked |
| 0 | `exact` | Exact integer arithmetic |

`start`, `stride`, `initial`, and all term literals are JSON integers. For a
signed request, `start`, `initial`, and term literals must fit the selected
width. `stride` is the exact nonzero step of the mathematical progression. It
can be negative and is subject to the SDK's 1,024-bit range bound.

| Term | Exact fields |
| --- | --- |
| Index | `{"op":"index"}` |
| Integer literal | `{"op":"integer","value":2}` |
| Addition, subtraction, or multiplication | `{"op":"add","left":{"op":"index"},"right":{"op":"integer","value":2}}` with `op` equal to `add`, `subtract`, or `multiply` |

For a nonnegative runtime count `n`, the verified plan gives
`initial + sum(coefficients[j] * binomial(n, j + 1))` for the sum of `term`
at `start + stride*k`, for `0 <= k < n`. The response echoes the request JSON
value and returns exact integer coefficients, which can exceed 64 bits. Read
them losslessly and evaluate the formula with exact intermediates. In `wrap`
mode, reduce the result modulo `2^bits` and interpret it as signed.

The CLI uses the SDK's polynomial limits (including index degree 8, expression
depth 64, and coefficient size 4,096 bits), with separate search and verification
budgets of 100,000 work units and a 5-second timeout each. It writes no response
on rejection, reports the reason to stderr, and exits nonzero. The caller
establishes source admission, a valid count and terminating counter behavior,
and preservation of source effects. For `checked` arithmetic, the caller also
preserves the overflow behavior of term operations and every accumulation
prefix.


## Proof-bound explanations and contextual residues

`ComputationExplanations.describe(request, candidate, cancellation)` independently
reverifies a candidate and exposes immutable, typed original/replacement DAGs,
exact assumptions and runtime obligations, and original/candidate `modPow`
counts. No explanation is returned for an unverified candidate, a stale receipt,
a canceled request, or an exhausted explanation budget. The presentation is not
itself a proof. Consumers should render actual local names, retain necessary
runtime guards, and keep operation-count claims separate from measurements.

The explanation result currently reports presentation work separately from the
fresh verification/replay performed by this call. It is not a complete consumed-work
receipt; summing it with `Candidate.work()` does not account for every executed SDK
operation. Complete explanation-call accounting remains a separate API/consumer
follow-up. The call's proof, cancellation and admission checks still apply.

The modular domain can eliminate `modPow(a, 1, m)` at a use inside a matching
reducing modular product, even when `a` is negative or unreduced. This is not a
license to replace a standalone normalized result by its raw base, or to use a
different modulus. Both original and proposed traces retain Java numeric checks.
In particular, related powers `a^(2e+1)` and `a^(e+1)` can share one `a^e` and two
modular products without introducing a second exponentiation of exponent one.

This SDK change does not derive constructor-field, loop, or helper-method
contracts from arbitrary Java code. The complete historic Bouncy Castle #2455
constructor remains a source-adapter milestone, not a claimed acceptance result.

## Recorded selected search paths

Optimizer-produced candidates expose `derivation()`. It contains the exact retained
`JointPlanSearch` witness, with rule identifiers and typed before/after output envelopes.
There is no second search and no source-name-specific explanation. A compound proposal
is one recorded edge; it is not expanded into invented primitive identities.

`reverify` and `ComputationExplanations.describe` replay a present derivation: evidence
binding, contiguous endpoints, regeneration of each claimed rule/target from the recorded
generation configuration, and independent numeric checking of every edge are required.
The generation configuration is not a work grant: every replay operation consumes the
one caller allowance and original deadline shared with final proof and presentation.
Cancellation, exhaustion or invalid history produces no partial successful explanation.
The original source trace and numerical policy remain authoritative for Java behavior.

The previous Candidate constructor remains available and explicitly means unrecorded
history. An absent history differs from a recorded zero-edge path (an unchanged value
graph with a different prepared schedule). Neither is padded with a fabricated rewrite.
These immutable records are replayable data, not a signed audit log or a timing result.

Small constant modular powers (exponents 2 through 16) additionally propose a
bounded binary multiplication chain through the existing modular domain. The
ordinary independent proof still requires a positive modulus; no assumption is
inferred from a method or variable name. Literal-exponent cost estimates include
a fixed setup allowance (32 work units plus twice the exponent bit length), so
a modular square can compete with `modPow`. This is a search-ranking heuristic,
not a measured latency or a constant-time guarantee; consumers must account for
their own receiver guards and fallback costs.


Plain `BigInteger.mod` now participates in the modular search bridge. A bounded,
independent multiplicative residue normal form verifies nested reductions,
computed bases and products with several bases. A reduction can disappear only
when the result is already normalized for the same positive modulus; a bare
unreduced product remains inadmissible. Additive subexpressions are treated as
exact atoms, and nested exponents retain the affine and structural proof bounds.

This allows the small-power candidate to be found inside a larger expression
such as `x.modPow(TWO, m).add(x).mod(m)`. The adapter must still establish receiver
and range contracts and account for the cost of any generated guards. In
particular, removing one `mod` does not necessarily pay for new receiver checks.

The modular bridge also transports `BigInteger.shiftLeft(int)` for established
literal distances from 0 through 4095, using the exact integer identity
`x * 2^distance`. Signed bases are supported. Scalar distances keep their Java
`int` type when restored, and unchanged output/context branches keep their
original operations. Negative, variable, unevaluated and oversized distances
remain outside this bridge fragment. Both original and replacement magnitude
checks, positive-modulus requirements and original trace work remain mandatory.

An optimizer-produced `Inconclusive` result reports consumed work so adapters can
charge bounded retries without discarding the entire unused allocation. The old
single-argument constructor remains available and marks work as unknown (`-1`);
consumers must conservatively reserve the full allocation for that legacy case.

## Established premises for core algebra

The ordinary core rule inventory can now use the caller's established `POSITIVE`
and `NON_NEGATIVE` facts. A `NON_NEGATIVE_UPPER_BOUND` also establishes
nonnegativity. The adapter resolves each canonical `NON_ZERO`, `POSITIVE`, or
`NON_NEGATIVE` rule premise against an actual encoded source subtree and its
original typed Java value. Fresh subjects, mismatched predicate text, ambiguous
subject renderings, and other predicate kinds remain unproved. Facts about an
input do not pass through an opaque cast or unrelated arithmetic atom.

Exact typed literals establish their own signs. Bounded sign reasoning for
BigInteger addition, subtraction, multiplication, and negation can discharge a
composite subject. For example, nonnegative `d` establishes that `d + 1` is
positive. Primitive arithmetic remains unknown to this sign fragment because
overflow can change its sign or turn a product into zero. No fact is added to
the request, and experimental rule packs retain their existing opt-in status.

Discharging a premise only authorizes a proposal. The independent checker proves
an exact quotient by establishing `N = D * Q` over integers and `D != 0`.
This supports BigInteger factor cancellation, including composite divisors, and
retains both source and replacement range gates for checked or guarded primitive
arithmetic. It does not certify rational distribution over Java truncating
division, undo earlier truncation, or cancel an overflowing primitive product in
`PRESERVE_JAVA`. Existing assumption guards, BigInteger magnitude contracts,
work limits, cancellation, and independent derivation replay still apply.

### Bounded consumer ranking and complete explanation work

`ComputationOptimizer.optimize(request, token, ranking)` lets a consumer price the
actual realization of a candidate while the existing verified frontier is being
explored. `CandidateRanking.Assessment` contains a nonnegative score and metered
inspection work, plus optional runtime work separate from the weighted score.
`assessSource` can price original occurrences separately from shared candidates.
Ranking cannot admit an unproved transformation; the final
independent checker and source trace remain authoritative. The two-argument entry
point preserves its existing cost policy.

`NumericExecutionCost.estimate` supplies optional operand-magnitude-sensitive
word-work and live-storage estimates. `estimateSource` includes repeated original
occurrences. Missing bounds return no estimate; estimates are not measurements,
proofs, or claims about constant-time behavior. Consumers must still price their
own guards, captures and emitted code.

`ComputationExplanations.Result.work()` includes fresh independent verification,
derivation replay and presentation. The overload with `remainingWork` bounds the
call without changing the original search request. Legacy manually constructed
results report unknown work (-1); consumers must reserve their allowance for those.
Completed preparation work remains reported on budget exhaustion and cancellation,
so reported work may exceed the allowance when an indivisible preparation finishes.

The v12 checker proves low-word polynomial congruences and carry-free addition of
disjoint bit ranges. Generic proposals remove redundant masks under a common low
word observation and reconstruct high/low words joined by addition. Wrong masks,
widths, signs and overlapping carry bits remain unproved. There are no source-name
or cryptography-specific recognizers.

