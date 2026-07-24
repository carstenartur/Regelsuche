# Independent autonomous-discovery reproduction

Regelsuche packages the qualified autonomous-discovery walkthrough as a frozen,
independently executable artifact. The repository verifies deterministic
packaging, artifact integrity, fail-closed receipts and isolated container
execution. External mathematical novelty and independent third-party
attestation remain separate claims.

## Authoritative checkout commands

The complete contract is owned by Gradle, JUnit/Testcontainers and checked-in
Python verifiers. It is runnable from an ordinary Git checkout:

```bash
./gradlew verifyIndependentReproductionArtifact
./gradlew independentReproductionContainerTest
./gradlew fullCheck
```

`verifyIndependentReproductionArtifact` generates the qualified walkthrough,
builds the artifact twice into isolated output directories and verifies the
complete lifecycle. `independentReproductionContainerTest` builds the public
`independent-reproduction` Docker target from the generated artifact and runs
it with the evaluated network disabled. `fullCheck` includes both contracts.

GitHub Actions is only a provisioning and artifact-retention adapter. It does
not define expected files, hashes, negative cases, receipt semantics or Docker
execution policy.

## Generated outputs

The development lifecycle writes below `build/independent-reproduction/`:

- `artifact-a/` and `artifact-b/` — independently generated artifact trees;
- `artifact-a.tar.gz` and `artifact-b.tar.gz` — canonical bundle archives;
- `reports/lifecycle-verification.json` — machine-readable lifecycle result;
- `reports/lifecycle-summary.md` — human-readable verification summary;
- retained positive and negative reproduction receipts.

The two artifact trees and archives must be byte-identical. The archive uses the
single canonical top-level directory
`regelsuche-independent-reproduction/`. Absolute paths, traversal entries,
duplicate members and symbolic or hard links are rejected.

## Frozen input identities

The artifact manifest binds:

- the exact Git commit, development or published release label and deterministic
  source archive;
- the digest-pinned Eclipse Temurin 21.0.11 image definition;
- the Gradle distribution URL and SHA-256;
- operating system, architecture, runtime user and network policies;
- proof-backend identity, version, invocation and retained result;
- Research Brief, inventory, model, candidate lineage, qualification suite and
  split identities;
- expected semantic roots and all portable exact-byte paths;
- schemas, public commands, license, citation and CodeMeta metadata.

By default the checkout tasks create a
`DEVELOPMENT_READY_FOR_INDEPENDENT_EXECUTION` artifact named
`development-<commit>`. A public artifact may be built with status `PUBLISHED`
only when the named Git tag resolves to the exact checked-out commit; the
builder then emits `FROZEN_PUBLIC_RELEASE`.

## Determinism and source-state guarantees

The builder fails closed unless:

- the requested revision is a real commit and equals `HEAD`;
- the worktree, including untracked files, is clean;
- the Gradle wrapper URL and digest match the frozen contract;
- the container base image and index digest match the frozen contract;
- the walkthrough and every required source, schema and launcher file exist as
  regular non-symbolic files.

The checkout-owned verifier repeats these checks independently. It also creates
an unreferenced commit identity to prove that revision substitution fails and
temporarily dirties a tracked file to prove that dirty-checkout rejection is
fail-closed and restores the original bytes and mode afterwards.

## Output ownership and marker safety

`reproduce.sh` treats an output directory as owned only when it is empty or
contains the exact regular marker
`.regelsuche-independent-reproduction-output`. A wrong marker, a symbolic
marker or an unrelated populated directory is rejected. Negative tests retain
unrelated sentinel files, so a failed run cannot silently erase user data.

Container-created output is handled through rootless/user-namespace-safe bind
mounts. Testcontainers performs best-effort permission normalization with the
same frozen image before temporary host directories are removed.

## Container and network boundary

The Testcontainers contract builds the real artifact Dockerfile and target,
not a test-only substitute. The evaluated run:

- executes as the image's non-root `reproducer` user (UID 10001);
- uses Gradle offline mode inside the image;
- has Docker network mode `none`;
- drops every declared Linux capability;
- enables `no-new-privileges`;
- retains the declared PID limit;
- receives only one writable host mount for observed evidence.

Dependency resolution is confined to the image build and is explicitly distinct
from the evaluated campaign. The evaluated run has no undeclared repository,
database, service or network dependency.

## Independent execution from the artifact

The extracted artifact requires Bash, Python 3.11 or newer,
`jsonschema==4.25.1` and Docker 24 or newer. Install the pinned validator, then
run the public launcher:

```bash
python3 -m pip install jsonschema==4.25.1
./reproduce.sh --output /path/to/reproduction-output \
  --environment-id anonymous-independent-run
```

The launcher verifies the immutable input before building or executing the
container. The complete observed evidence, logs and
`reproduction-receipt.json` remain under the selected output directory on both
success and failure.

## Receipt semantics

`regelsuche.independent-reproduction-receipt/v1` retains:

- semantic and complete hashes of the artifact manifest;
- expected and observed exact-byte roots;
- every missing, differing and unexpected path;
- expected and observed semantic roots and required statuses;
- input-verification failures and execution exit code;
- non-semantic environment diagnostics;
- `EXACT_BYTE_REPRODUCED`, `SEMANTICALLY_REPRODUCED` or `NOT_REPRODUCED`;
- semantic, reproducer-attestation and complete receipt hashes.

Timestamps, platform diagnostics, Docker version and the locally resolved image
ID are retained but excluded from semantic identity. The lifecycle verifier
proves that changing only those fields preserves `semanticReceiptHash` and
`reproducerAttestationHash` while changing the complete receipt hash.

Missing artifact input, an unreadable manifest and unexpected observed paths
all produce explicit machine-readable receipts. They are never normalized away
or converted into an apparent exact reproduction.

## Threat model

The checkout contract is designed to detect accidental drift and deliberate
substitution of the revision, worktree, manifest, expected files, archive
layout, output marker, launcher requirements, receipt roots or retained
statuses. It does not claim protection from a compromised host kernel, Docker
daemon, Git client, dependency registry, signing key or hardware platform.
Those trust anchors must be governed separately by an independent reproducer.

## Claim boundary

A green local or CI run establishes that maintainers can deterministically
package and reproduce the declared result under the frozen contract. It does
**not** establish external mathematical novelty, formal proof beyond the
retained backend evidence, publication acceptance or independent attestation.

Until a person or organization uninvolved in the evaluated implementation runs
a frozen public artifact on separately administered infrastructure and retains
or publishes the first receipt, `externalAttestationStatus` remains
`NOT_COLLECTED` and the independent-execution criterion remains open.
