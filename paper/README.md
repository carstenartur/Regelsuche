# Regelsuche methods paper

This directory contains the source-controlled foundation for the claim-bounded Regelsuche methods and systems paper tracked in issue #388.

## Status

The package is intentionally marked `FOUNDATION`. It defines the research question, claim inventory, limitations, artifact contract and reproducible build entry point. It does **not** yet contain final results from the candidate-independent benchmark (#383), information-parity comparisons (#235), end-to-end amortization study (#384), or independent reproduction (#387).

No pending claim may be promoted to a central empirical result by editing prose alone. The final build must consume retained canonical evidence and the claim-status source from #385.

## Build

```bash
./paper/reproduce-paper.sh
```

The foundation build performs deterministic structural checks and writes generated claim and artifact summaries below `paper/generated/`. A later revision will compile the archival PDF once the selected manuscript toolchain is frozen.

## Source of truth

- `manuscript.md` — working manuscript source;
- `claims-and-evidence.md` — central claim inventory and evidence dependencies;
- `limitations.md` — mandatory scientific limitations and non-claims;
- `artifact-appendix.md` — reproduction and artifact-evaluation contract;
- `paper-artifact-manifest.json` — canonical foundation manifest;
- `../docs/schemas/regelsuche-paper-artifact-manifest-v1.schema.json` — machine-readable manifest schema.

The repository's versioned Java and JSON evidence contracts remain authoritative for scientific status. This package summarizes and cites them; it does not replace them.
