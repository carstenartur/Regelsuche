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

## Assemble review inputs before execution

The custodian and scientific reviewers supply two public files. Obtain the canonical
`rewrite-architecture-decision.json` from the #620 historical development-atlas
workflow at its recorded commit. The producer hashes its canonical UTF-8 object
before appending the terminal `contentHash` property. The assembler reconstructs
the decision using the producer's own report representation and verifies that hash.

Prepare `preregistration.json` using the [non-secret representation example](../app/src/test/resources/de/regelsuche/evolution/flagship-review-inputs.json).
The [search-work example](../app/src/test/resources/de/regelsuche/evolution/flagship-search-review-inputs.json)
is a replacement for the entire `endpoint` object. Both examples are test inputs.
Replace the synthetic digests with reviewed artifact identities before the real
study. Compute `validationCommitmentHash` and `finalTestCommitmentHash` from the
two published split commitment objects (`contentHash`). Copy the exact #620
decision root. Supply SHA-256 identities for the implementation revision and its
retained characterization on each component, including the runtime image and
benchmark suite. For each included decision track retain the exact implementation
and evidence. For each deferred track state the limitation and reference its
corpus-coverage and claim-exclusion documents. For each baseline retain
implementation, configuration and translation-loss manifests, including
unsupported operations, assumption provenance and the applicable information
regime. The custodian archives the referenced documents and checks their
digests, coverage and scientific suitability before approving the public file.

Choose exactly one primary endpoint. For representation discovery, supply all
policy roots, the exact R1–R4 information profile, and the comparable #235
formation, transfer and ablation baselines. The expanded-cost metric charges
aliases and hidden definitions. The capability-frontier policy defines a material
unlock, and the bridge policy defines an independently checked held-out reuse.
The catalogue, held-out consequences, proof policy, ranking and visibility
are fixed before formation. Evaluate each predeclared comparator under the
same information and work limits and retain all outcomes.

| Review example | Positive criterion | Budget, exclusions and reproducibility | Transparent null |
| --- | --- | --- | --- |
| Search work | Improve at least two FINAL cases in two families. Reach one new case or reduce mechanical work by at least 100 permille under the frozen matched-work policy. | Eight-track plan and public TRAIN ledger (20,000 primitive units). At most 24 program nodes and 12 genome rules. Zero correctness, hidden-assumption and technical failures. Require two clean reproductions and a pinned environment. | Publish the complete registered case and baseline surface if the positive route is missed. |
| Representation discovery | Two FINAL cases in two families must each meet all three gates: at least 100 permille expanded-cost reduction, one new capability and one certified held-out reuse. | At most 20,000 declared work units per case. Zero correctness and assumption regressions. Require two clean reproductions and a pinned environment. | Report all cases and baselines with unmet criteria, including unsupported and inconclusive cases. |

The assembler checks threshold ranges and committed case/family counts. Scientific
reviewers assess whether the numerical thresholds and policy roots are suitable
for the actual corpus, including whether the declared capabilities can be reached.
Changes to any bound field produce a different receipt. The v2 receipt binds
`architectureDecisionHash` and `preregistrationHash`; the archived v1 schema still
describes historical receipts. To verify independently, hash all bytes of emitted
`preregistration.json` as UTF-8. For the architecture decision and freeze receipt,
drop the terminal `contentHash` property, restore the closing brace and hash those
canonical UTF-8 bytes; compare prefixed lowercase SHA-256 values. The protected
study, actual scientific review and authority-bearing receipt remain with the
[custodian](https://github.com/carstenartur/Regelsuche/blob/b74134a2ff47f933478fd7953488b99ab5a1b88b/docs/TODO-extern.md#flagship-studie).

## Stage 2 — assemble the public freeze

After both private bundles have been reviewed and sealed, run from a clean
checkout at the exact commit being frozen:

```bash
bash scripts/freeze-flagship-rewrite-program.sh \
  "$(git rev-parse HEAD)" \
  /private/validation-sealed.json \
  /private/final-test-sealed.json \
  /review/preregistration.json \
  /review/rewrite-architecture-decision.json \
  ./build/flagship-freeze
```

The wrapper fails before Gradle starts unless all of these conditions hold:

- the supplied lowercase 40-character commit equals the checkout's current
  `HEAD`;
- `git status --porcelain --untracked-files=all` is empty;
- both private bundles are regular files outside the repository tree;
- the public output path is absent or an empty directory.

The output-directory rule prevents stale result or receipt files from being
mistaken for part of the new freeze. Ignored build outputs do not make the
checkout dirty, but the selected freeze directory itself must still be empty.

The wrapper invokes the checkout-owned Gradle task through the versioned init
script:

```text
:app:freezeFlagshipRewriteProgram
```

The Gradle Java process runs with the repository root as its working directory,
so relative paths have one stable meaning across local environments.

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
