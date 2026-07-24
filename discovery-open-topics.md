# Discovery follow-up status

This document records which parts of the discovery roadmap are considered implemented by the current pipeline and which follow-ups remain intentionally out of scope.

## Implemented gate requirements

- Candidate novelty is classified before public promotion.
- Candidate lifecycle and support examples are tracked in the candidate store.
- Candidate reports carry structured ablation evidence when real with/without metrics exist.
- Public candidate evidence requires structured ablation metrics, not just a status string.
- The generated `docs/demo-gallery.md` source is protected by `PublicBenchmarkEvidenceGate`.
- Gallery generation writes `docs/generated/discovery/public-scenario-gate.json` and `docs/generated/discovery/public-scenario-rejections.md`.
- Generated campaigns may produce support examples, but synthetic generator metadata must not be accepted as public evidence until backed by real search provenance.

## Remaining future work

- Extend more campaign runners to execute true with/without candidate ablation runs instead of status-only reports.
- Add more generated mathematical families after the first deterministic generated campaign.
- Promote additional public gallery entries only after the public evidence gate accepts them.
