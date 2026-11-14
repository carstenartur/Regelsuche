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
5. requires the two foundation summaries, the public corpus split table, the controller tables, the candidate-slot allocation figure and the public recurrence formation and reuse tables;
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
- checks each public case identity and the freeze receipt before counting split assignments and formation access;
- binds the decompressed public controller matrix to its integrated revision and digest, then derives per-policy tables and a shared-scale SVG;
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

## Manuscript PDF and source package

From a clean Git checkout, provision the interpreter, renderer and command-line
versions in `paper/archive-toolchain.json` (including Python packages
`jsonschema==4.25.1`, `reportlab==4.4.9` and `Pillow==13.0.0`). The complete
source checkout and its Git metadata are needed when assembling a new package.
The build runs offline once these tools are installed.

```bash
revision=$(git rev-parse HEAD)
python3 -B scripts/build-paper-archive.py --revision "$revision" --output ../paper-first.zip
python3 -B scripts/build-paper-archive.py --revision "$revision" --output ../paper-second.zip
cmp ../paper-first.zip ../paper-second.zip
```

The archive contains the manuscript PDF with generated tables, allocation figure,
limitations, claims and artifact appendix. It includes the full committed source
and raw evidence in `provenance/source.tar.gz`, the original Git commit bytes,
the paper manifest, all six generated outputs, the license and citation files,
the renderer environment and a SHA-256 inventory. The top-level
`manifest.json` records the source commit and tree. The archive keeps the
working manuscript's `FOUNDATION` status and pending author declarations.

For an offline replay from the package, compare its expected revision with a
separately retained commit SHA. Extract the source from the package into a fresh
directory, inspect the build driver and execute it in the pinned environment:

```bash
unzip -p paper-first.zip provenance/source.tar.gz > source.tar.gz
mkdir paper-checkout
tar -xzf source.tar.gz -C paper-checkout
python3 -B paper-checkout/scripts/build-paper-archive.py --revision "$revision" \
  --replay paper-first.zip --output paper-replayed.zip
cmp paper-first.zip paper-replayed.zip
```

The replay verifies the commit and its complete Git tree, including executable
modes, before running the foundation verifier on the extracted inputs. It checks
every packaged file digest and reproduces the PDF and complete archive bytes.
Run `python3 -B scripts/test-paper-archive.py` for the independent source and
inventory mutation controls.
