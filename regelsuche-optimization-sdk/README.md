# Java computation optimization SDK

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

## Reproducible local distribution

The normal Maven/Gradle module coordinate is
`de.regelsuche:regelsuche-optimization-sdk:0.5.0-SNAPSHOT`, aligned by the BOM.
This is a build coordinate, not a claim that a public repository serves it.
The pinned standalone distribution uses
`0.5.0-issue1657-<commit12>` with classifier `all`, includes the dependency
closure, licenses, sources, Javadoc, consumer and SHA-256 provenance.

From a clean committed checkout, build with Java 25:

```sh
SDK_SOURCE_EPOCH=$(git show -s --format=%ct HEAD)
mvn -B -Psdk-release -pl regelsuche-optimization-sdk -am clean install \
  -Dproject.build.outputTimestamp="$SDK_SOURCE_EPOCH"
mvn -B -pl regelsuche-optimization-sdk dependency:copy-dependencies \
  -DincludeScope=runtime -DexcludeGroupIds=de.regelsuche \
  -DoutputDirectory=/absolute/external-runtime
python3 scripts/package-optimization-sdk.py \
  --external-dependencies /absolute/external-runtime \
  --output /absolute/distribution --java-home "$JAVA_HOME"
```

The commit timestamp fixes Maven input-JAR timestamps as well as the deterministic
outer archive. Use the same Java/Maven versions and flags for both clean builds;
the provenance includes raw input-JAR hashes, not only their class entries.
The packager refuses dirty sources, conflicting class bytes and forbidden
application dependencies. It merges service entries and keeps dependency
notices. Repeat the same build and packaging command, then compare every
artifact hash. JUnit owns packaging regressions; Python is only the production
build adapter. The normal API, publication, coverage and `ciCheck` authorities
include this module. See `docs/java-optimization-sdk-qualification.md` for the
fresh reconstruction evidence and any unresolved environment gates.


## Proof-bound explanations and contextual residues

`ComputationExplanations.describe(request, candidate, cancellation)` independently
reverifies a candidate and exposes immutable, typed original/replacement DAGs,
exact assumptions and runtime obligations, and original/candidate `modPow`
counts. No explanation is returned for an unverified candidate, a stale receipt,
a canceled request, or an exhausted explanation budget. The presentation is not
itself a proof. Consumers should render actual local names, retain necessary
runtime guards, and keep operation-count claims separate from measurements.

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

An optimizer-produced `Inconclusive` result reports consumed work so adapters can
charge bounded retries without discarding the entire unused allocation. The old
single-argument constructor remains available and marks work as unknown (`-1`);
consumers must conservatively reserve the full allocation for that legacy case.
