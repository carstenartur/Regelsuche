# Maven migration phase 2: additional Java/JUnit modules

This tranche extends the reactor introduced by #633 with another dependency-closed Java layer:

- `regelsuche-math-jas`;
- `regelsuche-persistence`;
- `regelsuche-solver-ir`;
- `regelsuche-solver-portfolio`;
- `regelsuche-learning`;
- `regelsuche-discovery`;
- `regelsuche-experiments`;
- `regelsuche-cli`.

The ordinary reproduction remains:

```bash
mvn --batch-mode --no-transfer-progress test
```

A focused example is:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-solver-portfolio -am test
```

These module test paths use Java 25, Maven Surefire and JUnit Jupiter. They introduce no host-side Python, Bash, Perl, Node/npm, Gradle invocation or GitHub dependency.

## Solver boundary

The ordinary `Z3SmtSolverBackendTest` uses injected process outcomes to characterize translation, proof-object, model and failure semantics. Its former `detectSystemZ3()` test was removed because a locally installed executable plus `assumeTrue` makes the result machine-dependent.

The real external-solver obligation is already covered by `ProofDockerImageIntegrationTest`: JUnit/Testcontainers starts the pinned proof image, verifies Z3 and cvc5, submits a real proof job and validates the retained SMT artifacts. Thus the implementation languages of the solvers stay inside Docker; no solver installation is required on the developer host.

Hibernate persistence, browser tests, other solver profiles, SymPy and other genuine foreign runtimes remain later Maven Failsafe/Testcontainers phases. Their implementation language is allowed inside pinned containers, not as a host prerequisite.

The Java generators and the current Python evidence verifiers in learning/discovery remain separate concerns. This phase proves that their production code and JUnit tests build under Maven; it does **not** authorize the Python verifiers as part of the final toolchain. Those verifier paths still have to be reimplemented in Java/JUnit before Gradle and Python can be removed.

## Claim boundary

This phase expands the executable Maven/JUnit reactor. It does not yet make Maven authoritative for the whole repository, and it does not claim that all evidence gates or integration tests have migrated.

## Proof Docker image: Maven application distribution

The `Dockerfile.proof` build stage now uses the exact-versioned JDK 25 /
Maven 3.10.0 builder and assembles the production application with
`mvn -Pfull -pl app -am -Dmaven.test.skip=true package`. The Maven assembly
archive is unpacked into the unchanged JRE 25 runtime, where the Maven
`bin/regelsuche` launcher starts the same `de.regelsuche.App` entry point.
The test skip applies only to image construction; the actual Z3/cvc5 and
Sophie-Germain proof workflow are exercised separately by
`ProofDockerImageIntegrationTest` under Testcontainers. The Docker context
is derived from the immutable tracked-file snapshot and excludes mutable
`build`/`target` outputs. The public source and license files required
by the distribution descriptor remain available to Maven.

The default `Dockerfile` Web Workbench runtime now uses the same checked Maven
producer, tar layout and `bin/regelsuche` launcher as `Dockerfile.proof`.
The `--target walkthrough` stage retains its separate legacy Gradle builder,
including its original research command. It is *not* redirected to a different
application workflow or silently removed. The standard image's runtime stage
depends only on the Maven distribution; the separate Gradle walkthrough stage
is not a prerequisite for it. The image policy explicitly retains the two
builder identities and the Temurin runtime image in their actual FROM order.

These are **two application image-production paths**, not a claim that the
walkthrough, all evidence producers, browser/SymPy profiles, other Dockerfiles,
CI or the release authority have migrated away from Gradle/host scripts.
Image construction still skips tests; actual Testcontainers Web Workbench and
proof-image checks must pass on the PR head and after integration. A green image
build cannot explain or repair the earlier sporadic Docker daemon failure.
No frozen reproduction Dockerfile, scientific receipt, quality threshold or
historical test corpus is changed.


## Maven-owned standard Web Workbench image verification

Hosted verification runs the Web Workbench image suite in the full Maven
checkout reactor.

```sh
mvn --batch-mode --no-transfer-progress -Pfull,sdk-release,web-workbench-image verify
```

For a focused local run, use `-Pfull,web-workbench-image -pl
regelsuche-integration-tests -am verify`.

The opt-in Maven Failsafe profile compiles and executes the **existing**
`WebWorkbenchDockerImageTest` with Testcontainers. It builds the final
`Dockerfile` image from the immutable tracked-file snapshot, allocates a
Testcontainers-managed random host port, waits for HTTP readiness and checks
HTML, JavaScript, styles, bundled vendor assets, the OpenAPI reference,
path-traversal rejection and both discovery API response structures.
`DockerAvailabilityIT` requires an actual reachable daemon, so an absent
Docker engine does not silently count as an image acceptance. CI also checks
that the report captured from the full checkout reactor contains executed image
tests with zero failures, errors and skips. This preserves the repository's
workflow-semantics contract: no manual `docker build`,
`docker run`, port binding or inline Python assertions in GitHub Actions.

The image suite is **explicitly checkout-only** because its current
reproducible build-context helper enumerates Git-tracked sources. The separate
Git-free `mvn -Pfull verify` archive replay remains unchanged and continues
to enforce the independent Postgres/Neo4j container suites. No result from the
new image suite may be claimed before its exact-head hosted Failsafe report
shows executed tests with zero failures, errors and skips. This does not yet
close #749's Maven-only A–E acceptance or diagnose an intermittent daemon failure.
