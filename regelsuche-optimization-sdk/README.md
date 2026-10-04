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
