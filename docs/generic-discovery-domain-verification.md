# Generic discovery-domain verification

The generic discovery-domain evidence gate is repository-owned and executable from a plain checkout:

```bash
bash scripts/run-generic-discovery-domains-verification.sh
```

The command requires Java 25. It creates a build-local Python environment pinned to `jsonschema==4.25.1`, runs all generic domain tests, validates the generated evidence, repeats the evidence-producing test and requires byte-identical output.

The verifier checks both retained adapters:

- `expression-rewrite` with a canonical equivalence-trace certificate;
- `integer-sequence-finite-difference` with a finite-difference witness.

For each adapter it validates the public domain-descriptor and discovery-evidence Draft-2020-12 schemas and checks:

- exact descriptor/seed identity binding;
- `CONFIRMED` outcome with retained candidate and certificate;
- explicit `NOT_EVALUATED` boundaries for proof, external novelty, promotion and Public Evidence;
- summary counts against retained states, transitions and candidate attempts;
- unique state hashes;
- the exact five shared resource roles;
- `configured = executed + skipped + remaining` for every resource.

Domain-specific checks retain the expression rule inventory and non-proof boundary, and the sequence difference order plus `[25,36]` holdout. Negative schema cases reject proof inflation and a non-deterministic v1 domain descriptor.

Evidence is retained below:

```text
regelsuche-discovery/build/reports/domain-discovery/
```

Repeated-run diagnostics are retained below:

```text
build/reports/generic-discovery-domains-ci/
```

The `Generic Discovery Domains` GitHub workflow only provisions Java/Gradle, invokes the same command and uploads the resulting evidence. It contains no evidence assertions, expected domain values, schema mutations or repeated-run logic.
