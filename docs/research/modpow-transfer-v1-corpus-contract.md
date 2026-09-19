# Frozen transfer v1: canonical corpus execution boundary

This is an execution-contract correction, not a new experiment or a revision of
`modpow-transfer-v1/`. All original TRAIN, TEST, model, result, freeze metadata
and plan bytes remain unchanged. The historical result remains YELLOW (3/4).

## Reproduced problem

The original public CLI accepted source-only corpora with arbitrary case
inventories and caller-provided positive/negative classifications. Its result
was relative to the supplied rows, not necessarily the preregistered five-row
TEST corpus. Removing REVERSED_FACTORS while retaining the other rows produced
GREEN (3 positive cases, 3 transfers, controlsClean=true). Removing the negative
control, relabeling a positive row or altering a bit profile also ran instead of
rejecting a non-canonical v1 input.

This does not change the result actually obtained with the retained canonical
corpus. It made subsequent invocations too permissive for an immutable v1
experiment. A model checksum alone cannot pin the separate TEST corpus.

## Correction

The public `ModPowFrozenTransfer.main` pins the corpus content identities from
commit `cbf07fdad3d80010e3af1b521c1fe9105a315db5`:

- TRAIN SHA-256: `c2932bb1a8a4412a819da52fb0b75ec62dae768b9e2261f19b1e7c6b6725c978`
- TEST SHA-256: `0ae4cdc2fc7335d672dfdadc99fb6b8c58b880e72605db9d74e155e21121472f`

The exact bounded byte array whose digest is checked is subsequently parsed.
There is no second unchecked read. The model's externally supplied checksum is
still verified BEFORE any TEST access. Rejected inputs produce no output file;
CREATE_NEW and the existing no-overwrite behavior remain. Copies at other paths
are accepted if their bytes are unchanged. Even reformatting is a different
content identity; new datasets require a separate experiment version.

The package-private `train` and `evaluate` fixture helpers remain available to
the existing development tests. They are explicitly not the public official-v1
entry point, and their arbitrary fixtures do not qualify as a frozen experiment.
No numerical cost, matching choice, proof rule, scientific threshold or verdict
formula was changed.

## Test-first evidence

Test-only head `915a4eaeb1922b0c798a58e41cdb20663a44c3bb`.
Hosted Java-25 run **35473754727**: all six existing tests and both positive
boundary controls pass; six new non-canonical-corpus controls fail by assertion.
No test errors or skips. Artifact **10594092035** was downloaded and rehashed:
`1cb00763837a9e7acbe99aa998cbb950aef8c863b38248d848034640646e6e88`.

The same failures were reproduced locally with actual retained source and
JUnit 6.1.3 under OpenJDK 21.0.11; this was source-subset compilation, not a full
repository build. After the correction, all 14 tests pass locally. The tested
and uploaded implementation blob is `6e2afdaa519e7f4a32fe68b29f1dc9cdfe2d4713`.
Fresh hosted Java-25 and complete current-head repository CI remain required;
no hosted correction result is predeclared in this record.

The canonical-copy positive test trains and evaluates through the public CLI
and compares EVERY model/result byte with the original gzip archives. The
other positive control checks that an incorrect model digest is rejected even
when the requested TEST file does not exist. The six negative controls cover a
removed failed case, removed negative control, relabeling, altered bit profile,
altered source with the same row inventory, and altered TRAIN data.

Reproduce the focused contract and prior workflow tests:

```sh
./gradlew --no-daemon --no-configuration-cache :app:test \
  --tests 'de.regelsuche.benchmark.ModPowFrozenCorpusContractTest' \
  --tests 'de.regelsuche.benchmark.ModPowFrozenTransferTest'
```

This boundary validates experiment input identity. It does not infer mathematical
premises or prove learned rules; independent replay remains unchanged.
