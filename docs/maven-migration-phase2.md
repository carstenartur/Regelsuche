# Maven migration phase 2: additional Java/JUnit modules

This tranche extends the reactor introduced by #633 with another dependency-closed Java layer:

- `regelsuche-math-jas`;
- `regelsuche-persistence`;
- `regelsuche-solver-ir`;
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
  -pl regelsuche-learning -am test
```

These module test paths use Java 21, Maven Surefire and JUnit Jupiter. They introduce no host-side Python, Bash, Perl, Node/npm, Gradle invocation or GitHub dependency.

## Deliberate exclusions

`regelsuche-solver-portfolio` is not included yet because one current test detects and executes a host-installed Z3 binary. That external boundary must first move to Maven Failsafe plus JUnit/Testcontainers and a pinned solver image; machine-dependent `assumeTrue` behavior is not an acceptable final build contract.

Hibernate persistence, browser tests, external solvers, SymPy and other genuine foreign runtimes remain later Docker/Testcontainers phases. Their implementation language is allowed inside pinned containers, not as a host prerequisite.

The Java generators and the current Python evidence verifiers in learning/discovery remain separate concerns. This phase proves that their production code and JUnit tests build under Maven; it does **not** authorize the Python verifiers as part of the final toolchain. Those verifier paths still have to be reimplemented in Java/JUnit before Gradle and Python can be removed.

## Claim boundary

This phase expands the executable Maven/JUnit reactor. It does not yet make Maven authoritative for the whole repository, and it does not claim that all evidence gates or integration tests have migrated.
