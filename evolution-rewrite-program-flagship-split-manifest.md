# Flagship split-manifest assembly

Status: pre-execution identity composition for #533 and #521

`FlagshipRewriteProgramSplitManifest` combines three already separated surfaces:

1. the public, reviewable `FlagshipRewriteProgramTrainCorpus`;
2. one trusted private VALIDATION reveal bundle;
3. one trusted private FINAL TEST reveal bundle.

The resulting `EvolutionSplitManifest` contains hashes and stable identities only.
It does not expose held-out expressions, targets, assumptions, difficulty labels or
expected terminal classes.

## Shared identity semantics

`EvolutionRewriteProgramTrainCaseReferences` derives TRAIN case references with
the same expression normalization, exact-signature and alpha-signature algorithm
used by the held-out reveal contract. This prevents a weaker TRAIN-only identity
implementation from overlooking a cross-split collision.

The manifest construction rejects collisions across TRAIN, VALIDATION and FINAL
TEST for:

- case IDs;
- structural families;
- exact signatures;
- alpha-normalized signatures;
- input identities;
- hidden-target identities.

Both held-out commitments are checked against the completed manifest before it is
returned.

## Private-material boundary

The builder accepts in-memory reveal bundles only inside a trusted local process.
It returns no concrete held-out value and persists nothing by itself. Public
commitment and split-reference files remain the only held-out artifacts eligible
for repository publication before their authorized reveal stages.

Changing any private case value changes the reveal-bundle hash, corpus hash and
complete split-manifest identity. Substituting another study or the wrong split
is rejected.

## Next stage

The completed manifest is one required input to
`EvolutionRewriteProgramFreezeReceipt`. A real `FROZEN_NOT_RUN` receipt still
requires the reviewed study plan, numerical thresholds, primitive inventory,
program grammar, mutation catalog, baseline/ablation plan, performance plan and
schema-bundle identities. Creating the manifest does not execute TRAIN, reveal
VALIDATION or consume FINAL TEST.
