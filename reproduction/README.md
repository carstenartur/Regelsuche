# Independent autonomous-discovery reproduction artifact

This immutable bundle reproduces the exact qualified Regelsuche autonomous-
discovery result identified by `artifact-manifest.json`.

> **REPRODUCTION DOES NOT IMPLY EXTERNAL MATHEMATICAL NOVELTY.**

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

The command verifies every manifest-bound file first. It then builds from the
bound source archive and a digest-pinned Eclipse Temurin image. Dependency
resolution is limited to the container build. The evaluated campaign runs as
UID 10001 with Gradle offline mode, no Linux capabilities and Docker
`--network none`.

The complete observed evidence, container logs and
`reproduction-receipt.json` remain under the selected output directory after a
success or failure.

## Reproduction levels

- `EXACT_BYTE_REPRODUCED`: every designated portable byte and every semantic
  root matches;
- `SEMANTICALLY_REPRODUCED`: all semantic roots and required statuses match,
  while one or more declared portable byte comparisons differ;
- `NOT_REPRODUCED`: an input, required root, status, execution or invariant
  differs.

Timestamps, host diagnostics and the locally resolved container image ID are
recorded in the receipt but excluded from its semantic identity. Missing,
differing and unexpected files are retained rather than normalized away. Even
an unreadable manifest produces a fallback failure receipt when the verifier
and receipt schema are still available.

Verify a retained receipt with:

```bash
python3 scripts/verify-independent-reproduction.py verify-receipt \
  --root . \
  --receipt /path/to/reproduction-receipt.json
```

## External protocol

An independent reproducer should obtain this archive without maintainer-local
changes, verify its published SHA-256, execute the first run on separately
administered infrastructure, retain that first receipt and logs, and report any
deviation before changing inputs. A correction receives a new artifact
identity; this bundle must never be replaced in place.

The public receipt needs no personal name. A separate signed organizational or
individual statement may bind `reproducerAttestationHash`.

`externalAttestationStatus` is deliberately `NOT_COLLECTED` in this bundle.
Repository CI is a maintainer self-test, not the independent execution required
to finish issue #387.
