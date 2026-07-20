# Independent autonomous-discovery reproduction artifact

This immutable bundle reproduces the exact qualified Regelsuche autonomous-
discovery result identified by `artifact-manifest.json`.

> **REPRODUCTION DOES NOT IMPLY EXTERNAL MATHEMATICAL NOVELTY.**

The repository that created this bundle verifies the same public launcher,
container target and receipt semantics through Gradle and Testcontainers. This
artifact remains independently executable and does not require the source
checkout.

## Host requirements

The launcher requires Bash, Python 3.11 or newer, `jsonschema==4.25.1` and
Docker 24 or newer. Install the pinned Python validator before the first run:

```bash
python3 -m pip install jsonschema==4.25.1
```

These requirements are declared in the manifest. No checkout, database,
maintainer configuration or host-side Gradle installation is used.

## Verify and run

Verify the immutable input without executing the campaign:

```bash
./reproduce.sh --verify-only
```

Run the complete reproduction:

```bash
./reproduce.sh --output "$PWD/../reproduction-output" \
  --environment-id anonymous-independent-run
```

The command verifies every manifest-bound file before it builds or executes
anything. It then builds from the bound source archive and digest-pinned Eclipse
Temurin image. Dependency resolution is limited to the image build. The
evaluated campaign runs as UID 10001 with Gradle offline mode, no Linux
capabilities, `no-new-privileges`, the declared PID bound and Docker network
mode `none`.

Only the selected output directory is writable. It must be empty or contain the
exact regular marker `.regelsuche-independent-reproduction-output`; a wrong or
symbolic marker is rejected without deleting unrelated files.

The complete observed evidence, container logs and
`reproduction-receipt.json` remain under the selected output directory after a
success or failure.

## Frozen identities

`artifact-manifest.json` binds the exact source revision, release-label status,
source archive, wrapper checksum, container image digest, architecture,
container target, runtime user, network policy, proof backend, Research Brief,
model, candidate lineage, qualification suite, split identities, schemas and
expected evidence roots.

The archive has one canonical top-level directory. Absolute or traversal paths,
duplicate entries and symbolic or hard links are not permitted. Corrections
must receive a new immutable artifact identity; this bundle must never be
replaced in place.

## Reproduction levels

- `EXACT_BYTE_REPRODUCED`: every designated portable byte and every semantic
  root matches;
- `SEMANTICALLY_REPRODUCED`: all semantic roots and required statuses match,
  while one or more declared portable byte comparisons differ;
- `NOT_REPRODUCED`: an input, required root, status, execution or invariant
  differs.

Timestamps, host diagnostics, Docker version, platform and the locally resolved
container image ID are recorded in the receipt but excluded from semantic
identity. Missing, differing and unexpected files are retained rather than
normalized away. Even an unreadable manifest produces a fallback failure
receipt when the verifier and receipt schema remain available.

Verify a retained receipt with:

```bash
python3 scripts/verify-independent-reproduction.py verify-receipt \
  --root . \
  --receipt /path/to/reproduction-receipt.json
```

## Trust and threat boundary

The bundle detects substituted or changed manifest-bound files, unsafe archive
layout, mismatched expected evidence, launcher-policy drift and receipt-hash
tampering. It does not make a compromised host kernel, Docker daemon, Git
client, dependency registry, signing key or hardware trustworthy. An
independent reproducer must govern those trust anchors separately.

## External protocol

An independent reproducer should obtain this archive without maintainer-local
changes, verify its published SHA-256, execute the first run on separately
administered infrastructure, retain that first receipt and logs, and report any
deviation before changing inputs.

The public receipt needs no personal name. A separate signed organizational or
individual statement may bind `reproducerAttestationHash`.

`externalAttestationStatus` is deliberately `NOT_COLLECTED` in this bundle.
Repository CI is a maintainer self-test, not the independent execution required
to finish the external-attestation criterion.
