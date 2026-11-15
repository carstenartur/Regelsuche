# Regelsuche methods paper

This directory contains the source-controlled foundation for the claim-bounded Regelsuche methods and systems paper tracked in issue #388.

## Status

The package is marked `FOUNDATION`. Its public results include target-directed search, target-free simplification, equality validation and shared-fragment rewriting baselines, the four Track C forms and historical inventory audit, paired cross-family transfer, the original and seven-stage controller comparisons, and discovery-component controls. The claim registry separately tracks the complete studies and independent reproduction.

No pending claim may be promoted to a central empirical result by editing prose alone. The final build must consume retained canonical evidence and the claim-status source from #385.

## Build

```bash
./paper/reproduce-paper.sh
```

The foundation build performs deterministic structural checks and writes the claim and artifact summaries, the case split table, the per-policy results for the public controller campaign and its allocation figure, the recurrence formation and reuse tables, the cross-form and transfer measures, and the seven-stage dispatch figure below `paper/generated/`. The checkout can also render the methods manuscript and assemble the complete source and evidence package. See [PDF build and offline archive replay](../docs/paper-foundation-verification.md#manuscript-pdf-and-source-package) for the pinned tools and commands.

## Source of truth

- `manuscript.md` — working manuscript source;
- `claims-and-evidence.md` — central claim inventory and evidence dependencies;
- `limitations.md` — mandatory scientific limitations and non-claims;
- `artifact-appendix.md` — reproduction and artifact-evaluation contract;
- `paper-artifact-manifest.json` — canonical foundation manifest;
- `../docs/schemas/regelsuche-paper-artifact-manifest-v1.schema.json` — machine-readable manifest schema.

The repository's versioned Java and JSON evidence contracts remain authoritative for scientific status. This package summarizes and cites them; it does not replace them.
