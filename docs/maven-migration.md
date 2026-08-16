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
