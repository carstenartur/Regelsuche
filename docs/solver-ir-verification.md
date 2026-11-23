# Solver IR verification

## Maven/JUnit evidence verification (new, additive)

The Maven reactor now generates the actual Solver IR example in a temporary
JUnit directory twice. It checks byte-identical output, validates both
obligations and all three backend results against the checkout's Draft 2020-12
schemas using the repository-pinned Java validator, and independently checks
obligation hashes, backend status, translation status, capability, message and
unsupported-assumption evidence. Negative tests distinguish a structurally
invalid JSON result from a structurally valid but falsely rebound or promoted
result. Nothing in these synthetic controls claims an external formal proof.

Run with JDK 25 and Maven:

```sh
mvn -B -ntp -pl regelsuche-solver-ir -am \
  -Dtest=SolverIrMavenEvidenceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

This is an **additive Maven migration slice** for #749, not an assertion that
all Gradle and Python production paths have been replaced. The historical
Python verifier and its workflow remain as an independent transitional
reference until complete raw-result and negative-case parity, Maven/Failsafe
lifecycle ownership and clean-checkout/source-archive/container reproducibility
have all been demonstrated. Do not remove or weaken their existing gates on
the strength of this test alone.

The authoritative Solver IR verification is implemented in the repository and runs from a plain checkout:

```bash
bash scripts/run-solver-ir-verification.sh
```

The command executes the module's JUnit tests, writes the canonical Solver IR examples and validates the retained artifacts with `scripts/verify-solver-ir-evidence.py`. A build-local Python environment under `build/verification-venv` provides the pinned `jsonschema==4.25.1` validator without depending on GitHub Actions.

The verifier checks:

- the Draft 2020-12 obligation and result schemas;
- both canonical obligations and all three backend results;
- information parity between Search and polynomial-normal-form backends;
- exact obligation-hash binding;
- lossless confirmed outcomes for supported inputs;
- deterministic polynomial-normal-form capability and message identity;
- fail-closed `UNSUPPORTED`/`REJECTED` handling for unsupported assumptions;
- rejection before backend execution.

Generated evidence is written below:

```text
regelsuche-solver-ir/build/reports/solver-ir/
```

The `Solver IR` workflow is only an adapter around the same command and publishes its reports. It contains no expected outcomes, schema assertions or mutation logic.
