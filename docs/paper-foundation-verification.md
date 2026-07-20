# Paper foundation verification

The paper directory is a claim-bounded **foundation**, not a completed research
result. Its manifest fixes the manuscript, claim registry, limitations,
artifact appendix, reproduction entry point and the evidence issues that must be
completed before stronger empirical or novelty claims are permitted.

## Authoritative command

Run the complete verification from an ordinary checkout:

```bash
./gradlew verifyPaperFoundation
```

The task is part of root `./gradlew check`. It uses the pinned repository Python
environment and does not require GitHub Actions.

## Verification layers

`verifyPaperFoundation` delegates to
`scripts/verify-paper-foundation-reproduction.py`. The orchestrator:

1. validates the fail-closed Draft 2020-12 manifest schema with the pinned
   `jsonschema` version;
2. runs `scripts/verify-paper-foundation.py` twice in isolated temporary output
   directories;
3. compares both complete output trees byte-for-byte;
4. compares the reproduced tree with committed `paper/generated`;
5. requires exactly `foundation-verification.json` and
   `foundation-summary.md`;
6. verifies that the receipt binds the manifest content hash and all required
   evidence issue IDs;
7. requires `status=FOUNDATION`, pending central claims and
   `externalNoveltyAuthorized=false`;
8. rejects duplicate evidence issues, escaping source paths, duplicate source
   paths and a premature `COMPLETE` status;
9. checks the claim registry and manuscript for the mandatory pending and
   unauthorized boundaries.

The underlying verifier additionally:

- rejects duplicate JSON fields;
- rejects symbolic manifest, schema, source and output paths;
- restricts every listed source to the `paper/` tree;
- checks every retained source SHA-256;
- recomputes the canonical manifest `contentHash`;
- requires the limitations and foundation-only manuscript language.

## Scientific boundary

A green task establishes only that the foundation is internally consistent,
reproducible and conservative about its claims. It does **not** establish:

- successful candidate-independent discovery;
- superiority to information-parity baselines;
- amortized end-to-end utility;
- external mathematical novelty;
- completion of the independent reproduction artifact;
- completion of an expert interestingness study.

Those claims remain governed by the evidence issues named in
`paper/paper-artifact-manifest.json`.

## CI boundary

A dedicated Paper Artifact workflow is unnecessary because it previously
performed only local verification. Central CI invokes the same Gradle contract
and retains repository reports. Future journal, archive or DOI publication may
use a separate write-capable workflow, but executable expectations must remain
in Gradle and checked-in verifiers.
