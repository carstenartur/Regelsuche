# Solver IR verification

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
