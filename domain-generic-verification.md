# Domain-generic qualification verification

The complete `DOMAIN_GENERIC_DISCOVERY` qualification gate is repository-owned and executable from a plain checkout:

```bash
bash scripts/run-domain-generic-qualification-verification.sh
```

The command requires Java 21. It creates a build-local Python environment with `jsonschema==4.25.1`, executes the dedicated JUnit characterization and qualification runner, validates the retained evidence, repeats the complete qualification and requires byte-identical output.

The verifier checks:

- all root, per-run, per-domain and verification-receipt artifacts;
- the normative trust-boundary and consumer-verification documentation sections;
- fail-closed Draft 2020-12 schemas for profile catalog, qualification report and run receipt;
- the exact bounded `DOMAIN_GENERIC_DISCOVERY` claim without broadening `AUTONOMOUS_CAMPAIGN`;
- all fourteen required qualification checks and both qualified domains;
- confirmed candidates, certificate identities, shared resource roles and balanced accounting;
- representation-free lifecycle handoff;
- explicit `NOT_EVALUATED` boundaries for proof, external novelty, promotion and Public Evidence;
- byte identity across all three clean multi-domain runs;
- independently reconstructed clean-run fingerprints;
- profile-catalog, report and run content hashes;
- negative schema cases for autonomy/proof inflation, one-domain readiness, alternative claims, failed checks, alternative requirements, incomplete resources and refuted domains.

Evidence is retained below:

```text
regelsuche-release/build/reports/domain-generic-qualification/
```

Repeated-run diagnostics are retained below:

```text
build/reports/domain-generic-qualification-ci/
```

The `Domain Generic Qualification` GitHub workflow only provisions Java/Gradle, invokes the same command and uploads the resulting evidence. It contains no qualification assertions, expected hashes, schema mutations or claim decisions.
