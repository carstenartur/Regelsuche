# PR #1065 P04 structural index evidence

Source head: `596afa383d9605a3dbd656512ad8844c4c032e38`.
Source tree: `df9098a0f8eb658b4f56f78d633ac2b5fba68f05`.
Qualified base: `da1f33195339efe08380b47c36e83455c1e3815f`.

The archive contains the independent review and corrections, initial failures, complete affected-module test XML, successful reruns, original adversarial probes and their timeout/controlled-stop receipts, unchanged complexity-gate reports, and the source-bound public P03 comparison (unchanged generator, baseline, all results from two fresh JVMs). No sealed holdout was accessed.

Archive: `pr1065-p04-structural-index.tar.gz` (375113 bytes).
SHA-256: `cea7eb10b8d7181f42a9dfff1764899ebe24ac268ccd7898b1cf1089b817f0dc`.
The archive's `sha256.json` binds each contained evidence file.

Local validation: 2705 module tests pass; complexity gate passes with no policy changes; all 15 public P03 files match byte-for-byte in both fresh JVMs.

Hosted integration: [CI run 36982939083](https://github.com/carstenartur/Regelsuche/actions/runs/36982939083) is pending at creation of this evidence commit. Its final exact-head receipt will be added separately.

Scope remains partial P04. Public native coverage is `PARTIAL_ATOMIC_INVENTORY`, outcome `INCONCLUSIVE`, completion false. No lifecycle-performance, learning or full-roadmap completion claim.
