# Executing the flagship freeze

Status: trusted-local preregistration workflow for #533 and #521

This workflow ends at `FROZEN_NOT_RUN`. It does not start a TRAIN population,
open VALIDATION or consume FINAL TEST.

## Trust boundary

Concrete VALIDATION and FINAL TEST cases remain outside the repository. A trusted
local custodian prepares one private draft for each split. Drafts and sealed
private bundles must not be placed below the checkout, in ordinary CI artifacts
or in a web-publication directory.

Only these derived artifacts may be published before reveal:

- held-out commitments;
- hash-only split references;
- the completed split manifest;
- frozen study, threshold, baseline, performance and static-contract files;
- the `FROZEN_NOT_RUN` receipt.

## Stage 1 — exact validation and sealing

For each split, run `FlagshipHeldOutDraftSealCommand` in the trusted environment.
The command:

1. rejects unknown draft fields;
2. normalizes each case through the production `RevealCase` path;
3. evaluates those exact normalized values with the rational normal-form
   evaluator;
4. requires the declared terminal class;
5. rejects unresolved assumptions on a confirmed case;
6. writes no output when any case fails;
7. writes one owner-restricted private bundle and two public hash-only artifacts
   only after every case passes.

The validation receipt contains no local path and no expression. It retains only
study/split identity, case IDs, terminal classes and normal-form hashes.

## Stage 2 — assemble the public freeze

After both private bundles have been reviewed and sealed, run from a clean
checkout at the exact commit being frozen:

```bash
bash scripts/freeze-flagship-rewrite-program.sh \
  "$(git rev-parse HEAD)" \
  /private/validation-sealed.json \
  /private/final-test-sealed.json \
  ./build/flagship-freeze
```

The wrapper invokes the checkout-owned Gradle task through the versioned init
script:

```text
:app:freezeFlagshipRewriteProgram
```

The assembler binds:

- the eight-case public TRAIN suite;
- VALIDATION and FINAL TEST commitments and split references;
- one cross-split collision-checked manifest;
- exact rational evaluation protocol;
- eight primitive rewrite genes and two executable seed programs;
- topology mutation catalog and population policy;
- numerical positive/null-result thresholds;
- all eight baseline and ablation tracks;
- unit-aware benchmark/performance policy;
- primitive inventory, grammar, matched-work and schema-bundle identities;
- the clean repository commit;
- absent TRAIN, VALIDATION and FINAL TEST result roots.

Every prerequisite file is written before `freeze-receipt.json`. A partial output
directory without the receipt has no freeze authority.

## Public output

The output directory contains canonical JSON for the split manifest, TRAIN suite,
commitments, split references, evaluator, seeds, study plan, thresholds, static
contracts, baseline/ablation plan and performance plan. The receipt is written
last and must state exactly:

```text
status: FROZEN_NOT_RUN
TRAIN: NOT_EVALUATED
VALIDATION: NOT_EVALUATED
FINAL_TEST: NOT_EVALUATED
```

Repeated assembly from identical private bundles and the same repository commit
is byte-identical. Changing any private case, public plan or repository commit
changes the final receipt identity.

## Next permitted action

Only after review and publication of the complete freeze may the protocol-bound
TRAIN population runner be enabled. Held-out values remain unavailable during
TRAIN. VALIDATION may be opened only after terminal TRAIN evidence exists, and
FINAL TEST remains exactly once after a complete validation-selected
configuration is frozen.
