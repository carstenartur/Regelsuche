# Supply-chain evidence

Regelsuche generates a checkout-owned aggregate CycloneDX dependency inventory
from the dependency graph actually resolved by the multi-project Gradle build.
The authoritative local task is:

```bash
./gradlew verifySupplyChainInventory
```

The task produces under `build/reports/quality/supply-chain/`:

- `bom.json` — the raw aggregate CycloneDX 1.6 document;
- `dependency-inventory.json` — a canonical component and dependency-graph view;
- `supply-chain-evidence.json` — policy, inventory hashes and accounting;
- `supply-chain-evidence.md` — a compact human-readable summary.

The raw document is generated directly from Gradle's resolved component graph.
No separate SBOM plugin-resolution path is introduced. Generated UUID serial
numbers, wall-clock timestamps and environment-specific build-system references
are omitted, so an unchanged resolved dependency graph produces unchanged raw
BOM bytes. The repository verifier then independently normalizes component and
dependency identities and rejects unknown graph references.

Synthetic verifier tests additionally prove that even an externally supplied raw
CycloneDX timestamp cannot alter the canonical inventory or evidence identity.

## Vulnerability boundary

This tranche does **not** claim that the listed dependencies are free of known
vulnerabilities. A vulnerability decision requires a scanner plus a
content-addressed advisory-database snapshot. The future scan contract must bind
at least provider, revision, creation time, SHA-256 and license, and it must fail
closed for unknown severity, scanner failure and expired or imprecise
suppressions.

Until that database and its raw scanner output are retained, the evidence states
`NOT_EVALUATED`. This prevents a successful SBOM generation from being mistaken
for a security assessment.
