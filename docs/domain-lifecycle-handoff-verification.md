# Domain lifecycle handoff verification

The domain-to-lifecycle handoff and autonomous production-generation export gate is repository-owned and executable from a plain checkout:

```bash
bash scripts/run-domain-lifecycle-handoff-verification.sh
```

The command requires Java 21. It creates a build-local environment pinned to `jsonschema==4.25.1`, runs the discovery and autopilot JUnit contracts, validates the generated handoffs/export, repeats both test suites and requires byte-identical evidence.

The verifier validates the fail-closed lifecycle-handoff and production-export Draft-2020-12 schemas and checks:

- expression and sequence discovery handoffs are `CONFIRMED` at `DISCOVERY_VALIDATION`;
- autonomous production handoffs are `COMPLETED` at `GENERATION` without fabricated candidate or certificate identities;
- the retained production handoff equals the exported handoff;
- proof, external novelty, promotion and Public Evidence remain `NOT_EVALUATED`;
- every resource ledger balances;
- the export uses `MANIFEST_LAST_ATOMIC_RENAME` and retains exactly seven declared artifacts;
- no temporary export files survive;
- export roles and file names are unique;
- every file length and SHA-256 byte hash matches the manifest;
- generation-run, lifecycle-handoff and source-evidence hashes are linked;
- the export-manifest content hash is independently reconstructed;
- no representation payload keys such as expressions, states, paths or sequence terms cross the handoff boundary.

Evidence is retained below:

```text
regelsuche-discovery/build/reports/domain-lifecycle-handoff/
regelsuche-autopilot/build/reports/domain-lifecycle-handoff/
```

Repeated-run diagnostics are retained below:

```text
build/reports/domain-lifecycle-handoff-ci/
```

The `Domain Lifecycle Handoff` GitHub workflow only provisions Java/Gradle, invokes the same command and uploads the resulting evidence. It contains no file assertions, byte comparisons, schema programs, expected values or representation-boundary logic.
