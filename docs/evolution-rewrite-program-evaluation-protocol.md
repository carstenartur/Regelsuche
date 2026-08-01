# Frozen evaluation protocol for evolved rewrite programs

Status: pre-freeze hardening for #521

## Why the TRAIN suite is not sufficient

A frozen TRAIN suite fixes cases, assumptions and resource budgets, but it does
not by itself determine the comparison. The same cases could still be evaluated
with a weaker baseline, a different target relation, a permissive correctness
check or resource metrics that reward an unused program.

`EvolutionRewriteProgramEvaluationProtocol` closes that substitution surface.
It is a separate, canonical and content-addressed artifact whose hash is stored
in both the preregistered study plan and every TRAIN fitness evidence root.

The v1 flagship protocol fixes:

```text
protocolId:
  information_parity_exact_rational_v1

baseline:
  ordinary rules + all candidate-genome rules as flat rules

candidate:
  the same baseline + the compiled rewrite program

target relation:
  syntax exact

correctness:
  exact rational normal-form validation for every retained path edge
  under the declared assumptions

resource attribution:
  only confirmed retained paths containing an actual program edge

implementation:
  ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator
```

The strict schema is
[`regelsuche-evolution-rewrite-program-evaluation-protocol-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-evaluation-protocol-v1.schema.json).

## Runtime binding

`ProtocolBoundEvolutionRewriteProgramPopulationRunner` is the authoritative
population entrypoint for the flagship campaign. Before delegating any TRAIN
work it verifies:

1. the study plan contains the evaluator protocol hash;
2. the evaluator object exposes the same protocol artifact;
3. the evaluator runtime class equals the implementation class frozen by that
   protocol;
4. the TRAIN suite evaluator profile agrees with the protocol;
5. every returned fitness artifact contains the same protocol hash.

A replacement evaluator is rejected before its first candidate evaluation.
Claiming the same protocol from another implementation class is also rejected.

The generic `EvolutionRewriteProgramPopulationEngine` remains useful for
isolated population-mechanics tests. It is not the authorized flagship
entrypoint and cannot by itself establish that the preregistered evaluator was
used.

## Evidence boundary

`EvolutionRewriteProgramTrainFitnessEvidence` now carries
`evaluationProtocolHash`. The protocol hash participates in the evidence content
hash, so post-hoc evaluator replacement changes the canonical evidence root.

The study plan carries `trainEvaluationProtocolHash`. A plan created for one
protocol cannot be executed or resumed through the protocol-bound runner with a
different protocol, even when suite, candidates, mutations and resource budgets
are otherwise unchanged.

## Claim boundary

This binding establishes evaluator identity and declared comparison semantics.
It does not prove that the implementation correctly realizes every semantic
statement. That remains covered by focused characterization, exact path
evidence, independent schema checks, reproduction and later independent result
verification. It also does not authorize VALIDATION or FINAL TEST execution.
