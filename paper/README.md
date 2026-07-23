# Regelsuche methods paper

This directory contains the source-controlled, claim-bounded Regelsuche methods and systems paper tracked in issue #388.

## Status

The package is marked `EVALUATION_IN_PROGRESS`.

The candidate-independent benchmark from #383 is complete and generated into the paper from its canonical v2 execution root. The paper may therefore report the bounded benchmark accounting: 12 executed campaigns, 72 frozen case slots, 52 successful slots, 20 retained no-result slots, 120 challenge-native detailed rows and zero correctness regressions.

The package is **not** an archival result release. Information-parity baselines and required ablations from #235 remain pending. #384 currently provides a vector cost ledger and bounded macro amortization reference, but not a complete end-to-end lifecycle break-even. External mathematical novelty and empirical expert interestingness remain unevaluated.

No pending claim may be promoted to a central empirical result by editing prose alone. The build consumes retained canonical evidence and the claim registry.

## Build

```bash
./paper/reproduce-paper.sh
```

The build executes the checkout-local candidate-independent benchmark evidence contract, generates `paper/generated/candidate-independent-benchmark.md`, verifies source hashes and claim boundaries, and writes deterministic verification summaries below `paper/generated/`.

The archival PDF toolchain remains deliberately unfrozen until the comparative and amortization dependencies are complete.

## Source of truth

- `manuscript.md` — working manuscript source;
- `claims-and-evidence.md` — central claim inventory and evidence dependencies;
- `limitations.md` — mandatory scientific limitations and non-claims;
- `artifact-appendix.md` — reproduction and artifact-evaluation contract;
- `generate-benchmark-results.py` — evidence-to-table generator for #383;
- `paper-artifact-manifest.json` — canonical paper manifest;
- `../docs/schemas/regelsuche-paper-artifact-manifest-v2.schema.json` — machine-readable manifest schema.

The repository's versioned Java and JSON evidence contracts remain authoritative. The paper summarizes and cites them; it does not replace them.
