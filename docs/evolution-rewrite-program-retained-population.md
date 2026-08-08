# Retained terminal rewrite-program populations

Status: executable TRAIN provenance required by the public showcase in #597

## Problem closed by this artifact

`EvolutionRewriteProgramPopulationEngine.PopulationRun` is intentionally a
compact canonical ledger. It retains final candidate identities, generation
reports, resource counters and terminal state, but not the complete genome and
program payload for every terminal candidate.

A later candidate freeze must not depend on transient Java objects or attempt to
reconstruct an evolved program from hashes and lineage. The retained population
artifact therefore binds the ordinary population run to the complete terminal
candidate payloads immediately when TRAIN finishes.

## Retained data

`RetainedEvolutionRewriteProgramPopulationRun` contains:

- the complete canonical `PopulationRun` JSON and its root;
- every final candidate hash and alpha-structural hash;
- canonical genome JSON;
- canonical executable rewrite-program plan JSON;
- the human-readable program rendering and its hash;
- the final-generation evaluation for every final candidate;
- raw fitness components, blockers, scalar fitness and evidence root;
- explicit `NOT_EVALUATED` states for VALIDATION and FINAL TEST.

The constructor reconstructs every candidate from its genome and plan, checks
all exact and alpha identities, recomputes the readable rendering, and requires
the retained candidate and evaluation roots to equal both the `PopulationRun`
final roots and the final generation's selected roots.

## Protocol-bound execution

`RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner` preserves the
same protocol checks as the ordinary protocol-bound runner. It records immutable
candidate payloads at the evaluator boundary and combines them with the
terminal roots after the engine has completed.

For resumed execution, the registry is seeded from the complete population in
the existing durable checkpoint. Candidates evaluated after resume are added at
the same boundary. The resulting retained terminal artifact must be byte
identical to uninterrupted execution.

No VALIDATION or FINAL TEST input is accepted or represented.

## Claim boundary

A retained population proves only that the terminal TRAIN candidates can be
reconstructed exactly from canonical payloads and that their final TRAIN
metrics remain bound to the ordinary population ledger.

It does not select a showcase candidate, freeze a public-randomness boundary,
generate FINAL TEST, prove utility, establish `SHOWCASE_CONFIRMED`, or support
external novelty or expert-reviewed importance.

## Schema and characterization

The strict Draft 2020-12 schema is:

```text
docs/schemas/regelsuche-evolution-rewrite-program-retained-population-run-v1.schema.json
```

The characterization requires:

- uninterrupted and checkpoint/resume execution to retain byte-identical
  terminal artifacts;
- every terminal root to have a complete canonical candidate and evaluation;
- candidate reconstruction from retained genome and plan JSON;
- rejection when final roots are detached from candidate payloads;
- explicit absence of later-stage outcomes.

This retained state is the authoritative input to the next reversible showcase
tranche: deterministic TRAIN-only candidate selection and creation of the
`CANDIDATE_FROZEN_FINAL_TEST_UNSEEN` artifact.
