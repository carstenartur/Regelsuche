# Regelsuche SDK integration contract for source adapters

The Java optimization facade is an additive module of the existing Regelsuche
SDK, with the same parent version, BOM, API policy and search implementation.
`regelsuche-discovery-sdk` remains the facade for user-defined discovery domains;
`regelsuche-optimization-sdk` supplies concrete Java numeric policies and their
independent checker on top of `JointPlanSearch`. It is not a separate product or
search engine. A standalone `all` JAR is one packaging of the same module and
runtime closure, not a fork of that SDK. Sandbox owns JDT extraction, source
emission, preview/apply/undo and source/environment freshness.

## Values versus source evaluations

These methods have the same value DAG but different operation traces:

```java
int shared(int x) {
    int sum = x + 1;
    return sum + sum;
}
int repeated(int x) {
    return (x + 1) + (x + 1);
}
```

Expanding outputs into expression trees cannot recover whether a source
operation ran once and its local value was reused. The source adapter therefore
provides every ordered operation occurrence, including duplicates and dead
operations. `SourceEvaluationTrace.fromPlan` remains a synthetic tree-trace
convenience; it is not a substitute for extracting an actual source trace.

The SDK checks the type, supported domain, safety and operand availability of
**each occurrence** and requires coverage of every operation in the value DAG.
It must not compare actual evaluation counts to counts from expanded outputs.
Original costs, runtime obligations and evidence hashes retain the entire
ordered occurrence list; that list is never deduplicated by the coverage check.

A shortened trace can describe another valid source program. Its independent
verification is not authorization to reuse evidence for the original program.
`reverify(originalRequest, candidate, token)` must bind the candidate to the
unaltered original trace, source assumptions and checker revision. The SDK
cannot prove that an adapter supplied a faithful trace of Java source it never
receives. The adapter must recheck source/environment freshness before applying.
The checker revision is now `java-numeric-independent/v4`; public API signatures
are unchanged, while evidence issued under previous checker revisions is stale.

## Current qualification boundary

The source-sharing regression tests were executed by Maven in CI run
`37339028975` on `7a9e0495326bb82b5d75cdea149a01726675aa29`: both shared-value
positive cases failed; the missing-operation negative passed. The SDK suite
contained 50 tests, two failures and no errors or skips. That is pre-fix evidence,
not a claim of current success.

`MavenOptimizationSdkDistributionTest` now owns the two real builds, fresh
standalone consumers, source/provenance checks and byte comparisons. It is enabled
by the existing `sdk-release` profile. The existing CI command already invokes
`mvn -Pfull,sdk-release verify`; no additional workflow or relaxed policy is needed.
For a focused qualification, run from a clean committed checkout with Java 25:

```sh
mvn --batch-mode --no-transfer-progress -Psdk-release -pl maven-build-contract \
  -Dtest=MavenOptimizationSdkDistributionTest test
```

The test calls the normal SDK Maven reactor and the existing production packager,
not a replacement build or numerical test framework. That nested reactor excludes
the build-contract module, so it cannot recursively call this test. Every original
SDK test must run without failures, errors or skips in each clean build. Each
packager invocation compiles and runs the real standalone consumer in a fresh
directory. Output hashes, source identity, manifest and complete distribution
bytes are checked by JUnit. Only after all checks pass is a success receipt written.

Evidence is retained beneath
`maven-build-contract/target/surefire-reports/sdk-distribution-*/` and is included
in the existing Maven artifact upload. Each run creates a fresh evidence directory;
old receipts cannot qualify a partial or failed execution. A profile-disabled
invocation does not count as distribution acceptance. The exact checked-out commit
is recorded, including a CI merge commit when that is the checkout being tested.

The first successful source-bound builds on `a2c069f298e2` executed 51 SDK tests
per build and produced identical complete distributions. However, introducing a
third workflow violated the repository's two-workflow governance test. That
workflow is removed rather than changing the policy; its successful SDK tests
remain historical evidence, not a pass of the complete repository contract.
Fresh execution through Maven/JUnit must qualify the current source.

Sandbox must consume the resulting actual JAR bytes and matching provenance,
not just edit a revision string alongside an older binary. The existing embedded
`b99787c55af6` artifact does not qualify these later SDK sources. Do not merge the
consumer on the strength of unrelated green SDK tests or close the umbrella issue
before the final SDK/adapter combination has been tested.
