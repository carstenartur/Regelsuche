# Release Readiness verification

The complete Release Readiness gate is repository-owned and executable from a plain checkout:

```bash
bash scripts/run-release-readiness-verification.sh
```

The command requires Java 21 and a reachable Docker daemon. It manages its pinned Python schema validator in `build/verification-venv`; GitHub Actions is not required.

The runner performs the complete contract:

1. executes the hidden-rule pilot JUnit test;
2. generates the qualified Release Readiness evidence through Gradle;
3. validates the local evidence root with `scripts/verify-release-readiness-evidence.py`;
4. builds `Dockerfile.release-readiness`;
5. reproduces the same qualified evidence in the runtime container;
6. validates the container-produced evidence independently;
7. compares local and container evidence byte-for-byte.

The evidence verifier checks:

- every mandatory release, campaign and qualification artifact;
- absence of the obsolete `proof-obligation.json` path;
- twelve Draft 2020-12 schema/artifact pairs;
- solver obligation/result/proof/lifecycle hash binding;
- `CONFIRMED` and `LOSSLESS` solver status;
- `READY` Hidden Rule, Open Target and Autonomous Campaign profiles;
- blocked External Novelty Review;
- the complete 20-case/four-family hidden-rule result and its negative holdouts;
- held-out split isolation;
- complete 12/12 positive and negative qualification execution;
- no refutations, counterexamples or correctness regressions;
- beneficial paired held-out utility;
- exact hidden-rule and qualification evidence bindings;
- explicit non-evaluation of Promotion and Public Evidence.

Local evidence is retained under:

```text
regelsuche-release/build/reports/release-readiness-qualified/
```

Container evidence is retained under:

```text
build/release-readiness-docker-output/
```

The `Release Readiness` workflow only provisions Java/Gradle, calls the same runner and uploads diagnostics. It contains no release assertions, expected values, schema programs or Docker lifecycle semantics.
