# Maven migration status

Issue [#632](https://github.com/carstenartur/Regelsuche/issues/632) reduces the
required developer toolchain to:

1. JDK 25;
2. Maven 3.9.9 or a newer Maven 3.x release;
3. Docker only for tests that require a real database, browser, solver or other
   external runtime.

Python, Bash, Perl, Node, npm and Gradle are not part of the target host-side
build contract. During the migration Gradle remains temporarily present only so
that the existing protected gate can verify each Maven tranche before the
single authoritative build is switched.

## Phase 1 scope

The first Maven reactor covers a coherent Java/JUnit core slice:

- `regelsuche-core`;
- `regelsuche-egraph`;
- `regelsuche-search`;
- `regelsuche-validation`;
- `regelsuche-math-algorithms`;
- `maven-build-contract`.

It compiles all main sources in these modules and runs their existing JUnit
Jupiter tests with Maven Surefire. The build-contract module additionally checks
that:

- the declared reactor modules and parent relationships are complete;
- Java 25 and the Maven 3.9.x range are fail-closed through Maven Enforcer;
- JUnit is executed through a pinned Surefire version;
- Maven POMs do not activate plugins or executables that introduce host-side
  scripting runtimes.

## Reproduce this tranche

From a source checkout or source archive:

```text
mvn --batch-mode --no-transfer-progress test
```

A focused module run including required upstream modules is:

```text
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-search -am test
```

The build-contract test can be run alone with:

```text
mvn --batch-mode --no-transfer-progress \
  -pl maven-build-contract -am \
  -Dtest=MavenBuildContractTest test
```

No GitHub API, Git checkout metadata or host-side interpreter is used by these
commands.

The Maven product CI also replays `-pl maven-build-contract -am test` in a
temporary directory unpacked from `git archive HEAD`. The Maven/JUnit
`regelsuche.sourceArchiveMode=true` guard checks that `.git` metadata is absent;
`failIfNoTests=true` prevents an empty test run from passing. All build-contract
tests run against tracked source files instead of relying on checkout metadata
or generated checkout outputs. Git is used by the CI adapter to *create* the
input archive, not by the Maven replay. The assertions remain in Java/JUnit,
not in GitHub Actions shell commands.

This is a **limited source-archive test**, not a demonstration of the complete
`mvn -Pfull verify` lifecycle from an archive. It does not yet qualify
Testcontainers, other authority-bearing modules, a Git-free host toolchain or
the Maven-only acceptance criteria A–E in
[issue #749](https://github.com/carstenartur/Regelsuche/issues/749).

## JMH decision-path migration (issue #749)

The active Gradle compatibility gate now invokes the Java/JUnit
`JmhRegressionV3Main` on JDK 25 with the six-warmup v2/v3 policy pair.
The historical Python verifier remains a synthetic comparison control;
the JMH producer, several other quality gates and their host-side
harnesses have **not** migrated. Missing, inconclusive and failed data
remain non-green. This is a measured step toward Maven ownership,
not evidence that `mvn verify` has replaced all CI decision paths.

## Split CI coverage decision (issue #749)

The required `verification` job uses the `verify-cross-authority-coverage`
Maven profile and Failsafe to join the isolated SymPy JaCoCo XML and apply the
existing coverage policy to all retained module reports. It writes the
canonical JSON and Markdown consumed by the existing coverage publisher and
evidence collector. The Gradle/SymPy measurements and Pages renderer remain
part of the current CI workflow. See the
[coverage evidence replay command](quality-acceptance-evidence.md#ci-retention-and-remaining-acceptance).

## Transitional verification

Until all modules and evidence gates have moved, the existing Gradle `check`
invokes this Maven reactor as a transition guard. That does not make Gradle part
of the target architecture; it prevents a Maven POM from drifting while the
remaining modules are migrated.

The migration is not complete until:

- every Java module is present in the Maven reactor;
- integration tests run through JUnit Jupiter and Maven Failsafe/Testcontainers;
- existing Python and required shell verifier logic has moved to Java/JUnit;
- CI invokes Maven directly;
- Gradle files and the Gradle wrapper are removed.

## External runtimes

A non-Java implementation such as PostgreSQL, Neo4j, Chromium, SymPy, Z3, cvc5
or Lean may run in a pinned Docker image. JUnit/Testcontainers must own startup,
resource limits, timeout, input/output binding and result classification. The
developer must not install those systems or their implementation languages on
the host.
