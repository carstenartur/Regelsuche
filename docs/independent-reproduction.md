# Independent autonomous-discovery reproduction

Issue #387 packages the qualified autonomous-discovery walkthrough as a frozen,
independently executable artifact. Packaging, execution and comparison are
machine-verifiable. External mathematical novelty is outside this protocol.

## Build the artifact

After generating the qualified result card for the exact repository revision:

```bash
REVISION="$(git rev-parse HEAD)"
./gradlew :regelsuche-release:runAutonomousDiscoveryWalkthrough \
  -PrepositoryRevision="$REVISION" \
  -PwalkthroughOutput="$PWD/build/walkthrough"

python3 scripts/build-independent-reproduction-artifact.py \
  --repository-root . \
  --walkthrough-root build/walkthrough \
  --source-revision "$REVISION" \
  --release-tag "development-$REVISION" \
  --release-tag-status DEVELOPMENT_REVISION \
  --output-directory build/independent-reproduction \
  --archive-output build/independent-reproduction.tar.gz
```

For a public frozen release, `--release-tag-status PUBLISHED` fails unless the
named Git tag resolves to the exact source revision. Development artifacts are
clearly marked `DEVELOPMENT_READY_FOR_INDEPENDENT_EXECUTION`; a published tag
produces `FROZEN_PUBLIC_RELEASE`.

The manifest binds:

- the source revision, release-label status and deterministic source archive;
- a digest-pinned Temurin 21.0.11 container definition;
- the Gradle 9.5.1 distribution URL and SHA-256;
- OS, architecture, non-root runtime and network policies;
- the proof backend ID, version, invocation and retained solver result;
- Research Brief, inventory, model, candidate lineage, qualification suite and
  split identities;
- expected semantic roots and all portable exact-byte paths;
- schemas, commands, license, citation and CodeMeta metadata.

Two builds into differently named directories must produce byte-identical
bundle archives. The archive itself always uses the fixed top-level directory
`regelsuche-independent-reproduction/`.

## Execute on separate infrastructure

The host requires Bash, Python 3.11 or newer, `jsonschema==4.25.1` and Docker
24 or newer. Install the pinned validator before verifying or executing the
bundle:

```bash
python3 -m pip install jsonschema==4.25.1
```

Extract the archive without modifying it and run:

```bash
./reproduce.sh --output /path/to/reproduction-output \
  --environment-id anonymous-independent-run
```

The launcher first checks the manifest, complete file inventory, source archive,
wrapper checksum, container base digest, result-card schema, cross-artifact
identities and proof backend. The container build uses only the immutable bundle
context. The evaluated run uses UID 10001, Gradle offline mode,
`--cap-drop ALL`, `no-new-privileges` and `--network none`.

Build-time dependency resolution is declared separately and is not part of the
evaluated campaign. The evaluated run has no undeclared network, repository,
database or host-path dependency other than the writable output mount.

## Receipt semantics

`regelsuche.independent-reproduction-receipt/v1` retains:

- semantic and exact hashes of the artifact manifest;
- expected and observed exact-byte roots;
- every missing, differing and unexpected path;
- expected and observed semantic roots;
- input-verification and required-status failures;
- execution exit code and non-semantic environment diagnostics;
- `EXACT_BYTE_REPRODUCED`, `SEMANTICALLY_REPRODUCED` or `NOT_REPRODUCED`;
- semantic, attestation-payload and complete receipt hashes.

The verifier independently recomputes all three receipt hashes and checks that
the status follows from the comparisons. Valid manifests with missing files,
evaluated-run failures and even unreadable manifests retain machine-readable
`NOT_REPRODUCED` receipts rather than hiding the first failure.

## Scope boundary

The repository workflow proves deterministic packaging and an exact
maintainer-controlled container reproduction. It also exercises a deliberate
failure and validates the retained failure receipt.

This does **not** satisfy the final independent-execution criterion. Until a
person or organization uninvolved in the evaluated implementation executes the
frozen public release on separately administered infrastructure and publishes
or returns its receipt, `externalAttestationStatus` remains `NOT_COLLECTED` and
issue #387 remains open.
