# Durable execution-bound rewrite-program checkpoints

Status: stacked implementation tranche for #613; requires the population execution protocol from #614.

## Why a second commit boundary is needed

`EvolutionRewriteProgramCheckpointArtifact` already provides a strict,
process-independent checkpoint artifact. Its three-file manifest-last protocol
binds the historical population checkpoint and all resumable TRAIN state.

That artifact predates the explicit population execution protocol. A historical
checkpoint therefore knows which study it belongs to, but by itself it cannot
prove which versioned proposal scheduling, mutator semantics and survivor
mechanics are authorized for resume.

A future scheduler must not be able to resume an old checkpoint merely because
the study hash still matches. The process-independent boundary must bind the
same execution identity as the in-memory checkpoint wrapper.

## Additive layout

`ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact` does not
replace or reinterpret the existing durable artifact. It stores that artifact
unchanged below one additional outer commit boundary:

```text
<artifact>/
  execution-checkpoint-binding.json
  checkpoint/
    checkpoint.json
    state.json
    checkpoint-artifact-manifest.json
```

The nested `checkpoint/` directory remains fully readable and verifiable by
`EvolutionRewriteProgramCheckpointArtifact`.

The outer `execution-checkpoint-binding.json` is written **last** and binds:

- the execution-bound checkpoint content root;
- the historical population-checkpoint root;
- the nested checkpoint-artifact manifest root;
- the frozen population execution-plan root;
- the frozen population execution-protocol root;
- commit protocol `OUTER_MANIFEST_LAST_ATOMIC_RENAME_V1`.

The strict language-neutral schema is
[`regelsuche-evolution-rewrite-program-execution-protocol-bound-checkpoint-artifact-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-execution-protocol-bound-checkpoint-artifact-v1.schema.json).

## Write protocol

A writer must:

1. reject symbolic-link ancestry and foreign entries;
2. remove any previous outer manifest before rewriting nested state;
3. delegate the nested payload to the existing checkpoint-artifact writer;
4. bind the returned nested manifest root to execution plan and protocol;
5. write the outer canonical manifest through a forced temporary file and
   atomic rename;
6. treat absence of the outer manifest as an incomplete, non-resumable export.

This preserves the existing checkpoint implementation as the authority for
TRAIN state while making execution identity a separate, reviewable layer.

## Read and resume protocol

A reader must fail closed unless all of the following hold:

1. the outer directory contains exactly the expected manifest and nested
   checkpoint directory;
2. the outer manifest is bounded UTF-8, strict JSON and canonical JSON;
3. its execution-plan and execution-protocol hashes equal the identities the
   caller requested;
4. the existing nested artifact verifies independently;
5. the nested checkpoint and nested manifest roots equal the roots retained by
   the outer manifest;
6. reconstructing
   `ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint` from the
   nested checkpoint and requested execution identity reproduces the retained
   execution-bound checkpoint root.

Only then may the loaded checkpoint be passed to the execution-protocol-bound
population runner.

## Reproducibility tests

The focused test suite requires:

- persist → reload → resume to equal the uninterrupted execution-bound run;
- repeated exports of one bound checkpoint to be byte-identical in the outer
  manifest and all nested payloads;
- absence of the outer manifest to fail closed;
- tampering with nested state to fail closed through the existing nested
  verifier;
- a different execution protocol/plan to fail before resume;
- the outer schema to remain strict and content-addressed.

## Claim boundary

This layer changes no mutation, population, evaluator or survivor behavior. It
is reproducibility infrastructure only.

In particular:

- showcase v1 is not rerun or reinterpreted;
- `STRATIFIED_MUTATION_KIND_V1` is still not executable;
- no VALIDATION or FINAL TEST data are introduced;
- no claim of improved search follows from durable checkpoint binding.

The scheduler implementation may proceed only after the legacy execution
identity and this durable resume boundary are green and reviewed.