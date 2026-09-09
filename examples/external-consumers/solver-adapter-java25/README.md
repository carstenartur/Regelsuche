# solver-adapter-java25

Requires Java 25 and the released SDK repository. From the extracted SDK bundle:

```bash
../../gradlew --project-dir . clean test run \
  -PregelsucheRepository=/absolute/path/to/regelsuche-sdk/repository \
  -PregelsucheVersion=0.5.0-SNAPSHOT
```

This standalone build consumes Maven coordinates only. `test` checks positive
and negative behavior and rejects unexpected runtime modules. `run` prints
retained evidence. The snapshot above is for checkout verification; use the
version in the release bundle's `sdk-compatibility.json` for released artifacts.
