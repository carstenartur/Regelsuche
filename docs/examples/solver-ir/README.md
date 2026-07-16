# Solver IR example artifacts

Generate the canonical example set with:

```bash
./gradlew :regelsuche-solver-ir:writeSolverIrExample
```

The generated directory `regelsuche-solver-ir/build/reports/solver-ir/` contains:

- one shared `obligation.json`;
- Search translation, result and atomic execution;
- exact polynomial-normal-form translation, result and atomic execution;
- one assumption-bound obligation;
- its rejected translation, unsupported result and atomic execution;
- a manifest binding the two successful backends to the same obligation.

The `Solver IR` workflow validates every generated artifact against the Draft-2020-12 schemas and checks the hash linkage between obligation, translation, result and execution.
