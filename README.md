# Retained recurrence lifecycle experiment

This branch retains the original full measurement bundle for the experiment reported in PR #1136. It is evidence storage, not a product release.

[Download the complete original ZIP](Regelsuche-Rekurrenz-Lebenszyklus-2026-11-03.zip)

- Archive size: 9,032,673 bytes
- Archive SHA-256: `a73e5d081f2f97a77b123a78f7a8f495e30d618866e2c0685f46cafc52623cb6`
- Evidence manifest: `sha256:d18cee6a3db145243881d6537f83278453328c353daf3012957e9ea6dd267ec6`
- Measurement source: `0d218aec11c8e610bf0097538c22b6c06eaa483b`
- Measurement source tree: `fb5cbf1bd6b498ec5b3c303199a00527d45ce42a`
- Protocol SHA-256: `14d05fdfd24541ffcba67474d4e948d344e54c228793252db4fb684e62ca8ba5`
- Runtime inventory binding: `sha256:e483e120bbdd2e1993970f04f794d4b2755f30818cfd5f5467a39277115f8a3f`

The ZIP includes all original responses, operations, actual saved snapshots, process CPU/RSS receipts, the full retained runtime bytes and inventory, protocol, command receipt, manifest, source metadata, reports and original verification logs. The frozen measurement is unchanged. Later verifier fixes can recheck these retained bytes without rerunning the experiment.

## Verify the recorded experiment

Check the archive SHA-256, unpack it, then use the verifier from PR #1136 (or main after its integration):

```bash
sha256sum Regelsuche-Rekurrenz-Lebenszyklus-2026-11-03.zip
unzip Regelsuche-Rekurrenz-Lebenszyklus-2026-11-03.zip -d recurrence-lifecycle-run1
python -m scripts.external_polynomial_comparison.run_recurrence_knowledge \
  --verify /absolute/path/to/recurrence-lifecycle-run1/evidence
```

Offline verification checks the recorded measurement; it does not start new JVMs or replace this experiment with a new run.

## Scope and observed result

The public development protocol executed 27 lifecycles, 657 queries and 45 actual JVM processes. All profiles achieved the registered identical success vector. At 64 queries, FAMILY used 71.4% less declared logical work, but its complete wall-time and CPU cost remained higher than BASELINE. Three repetitions on a shared host and one known recurrence family do not establish a general speedup or a break-even point.

See the report and numeric summary in PR #1136 for complete boundaries and limitations.