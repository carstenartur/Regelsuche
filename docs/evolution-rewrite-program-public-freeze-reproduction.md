# Public reproduction of the flagship freeze

The trusted flagship freeze command validates and seals concrete VALIDATION and
FINAL TEST material outside the repository. Its public output contains only
content-addressed commitments, hash-only split references, frozen plans and a
`FROZEN_NOT_RUN` receipt.

A published freeze must remain independently reproducible after the private
reveals have been returned to their custodian. The public reproduction path
therefore reconstructs the complete artifact directory from exactly four
public inputs:

- `validation-commitment.json`;
- `validation-split-references.json`;
- `final-test-commitment.json`;
- `final-test-split-references.json`.

It never opens either private reveal bundle.

## Command

From a clean checkout at the exact commit bound by the intended receipt:

```bash
scripts/freeze-flagship-rewrite-program-public.sh \
  "$(git rev-parse HEAD)" \
  path/to/validation-commitment.json \
  path/to/validation-split-references.json \
  path/to/final-test-commitment.json \
  path/to/final-test-split-references.json \
  build/flagship-freeze-reproduction
```

The command rejects a dirty checkout, a commit mismatch, missing or symlinked
inputs, malformed or oversized UTF-8, unknown JSON fields, trailing JSON values,
invalid content hashes, mismatched split roles, a substituted reveal root and a
non-empty output directory.

## Required comparison

The trusted private path and the public reproduction path must produce the same
set of files with byte-identical contents and the same `freeze-receipt.json`
content hash. Tests enforce this equivalence. A difference is a contract failure;
it must not be explained away by refreshing a baseline.

## Claim boundary

Public reproduction proves that the published commitment surface deterministically
binds one pre-execution study definition. It does not reveal held-out expressions,
authorize TRAIN execution, prove that a later reveal is correct, or turn
`FROZEN_NOT_RUN` into an empirical result. Stage-specific reveal authorization,
exact reveal verification, exactly-once FINAL TEST consumption and downstream
proof/novelty gates remain separate requirements.
