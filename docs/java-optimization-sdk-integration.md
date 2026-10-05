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

The `Regelsuche SDK consumer bundle` workflow executes the documented Maven
reactor and existing production packager twice from the exact PR source commit.
It retains the source/tree identities, Maven/JDK versions, complete Maven logs,
SDK test reports and compact source-bound distributions. Both builds must pass;
both fresh external consumers must pass; every distribution file must be
byte-identical. Failed builds may retain diagnostics but cannot create the
reproducibility success receipt. This gate does not replace the full existing
Regelsuche CI or the Sandbox installed-product and numerical-corpus gates.

Sandbox must consume the resulting actual JAR bytes and matching provenance,
not just edit a revision string alongside an older binary. The existing embedded
`b99787c55af6` artifact does not qualify these later SDK sources. Do not merge the
consumer on the strength of unrelated green SDK tests or close the umbrella issue
before the final SDK/adapter combination has been tested.
