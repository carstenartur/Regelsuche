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

## Protocol authority

A structurally complete payload alone is not proof that the frozen evaluator
contract was actually used. `ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun`
therefore wraps the retained population with:

- the exact `EvolutionRewriteProgramEvaluationProtocol` content hash;
- the exact evaluator implementation class;
- status `PROTOCOL_BOUND_TRAIN_RETAINED`;
- one content hash covering the protocol and complete retained payload.

The wrapper can be created only after the runner has checked that the study,
split manifest, TRAIN suite, mutation catalog, seed candidates, protocol and
implementation class are the frozen inputs. Candidate freezing consumes this
protocol-bound wrapper rather than a generic retained population.

## Operational bounds and trust boundary

The retained JSON is deliberately bounded by the strict schemas: the compact
population ledger is limited to 16 MiB, each canonical genome and plan to 8 MiB,
the readable program to 1 MiB, and the complete protocol-bound retained payload
to 32 MiB. A future standalone loader must enforce the same limits before
parsing and must reconstruct the runtime records rather than treating schema
validity or caller-supplied hashes as authority.

This tranche creates authority-bearing objects only inside the checked runtime
path. It does not yet claim that arbitrary JSON conforming to the schema is an
authorized retained TRAIN run.

## Protocol-bound execution

`RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner` preserves the
same protocol checks as the ordinary protocol-bound runner. It records immutable
candidate payloads at the evaluator boundary, combines them with the terminal
roots after the engine has completed, and finally creates the protocol-authority
wrapper.

For resumed execution, the registry is seeded from the complete population in
the existing durable checkpoint. Candidates evaluated after resume are added at
the same boundary. The resulting protocol-bound terminal artifact must be byte
identical to uninterrupted execution.

No VALIDATION or FINAL TEST input is accepted or represented.

## Claim boundary

A protocol-bound retained population proves only that the terminal TRAIN
candidates can be reconstructed exactly from canonical payloads, that their
final TRAIN metrics remain bound to the ordinary population ledger, and that
the declared frozen evaluator protocol and implementation were checked before
execution.

It does not select a showcase candidate, freeze a public-randomness boundary,
generate FINAL TEST, prove utility, establish `SHOWCASE_CONFIRMED`, or support
external novelty or expert-reviewed importance.

## Schemas and characterization

The strict Draft 2020-12 schemas are:

```text
docs/schemas/regelsuche-evolution-rewrite-program-retained-population-run-v1.schema.json
docs/schemas/regelsuche-evolution-rewrite-program-protocol-bound-retained-run-v1.schema.json
```

The characterization requires:

- uninterrupted and checkpoint/resume execution to retain byte-identical
  protocol-bound terminal artifacts;
- every terminal root to have a complete canonical candidate and evaluation;
- candidate reconstruction from retained genome and plan JSON;
- rejection when final roots are detached from candidate payloads;
- exact evaluator-protocol and implementation binding;
- explicit absence of later-stage outcomes.

This protocol-bound retained state is the authoritative input to the next
reversible showcase tranche: deterministic TRAIN-only candidate selection and
creation of the `CANDIDATE_FROZEN_FINAL_TEST_UNSEEN` artifact.
