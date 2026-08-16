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
