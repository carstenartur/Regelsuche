# Discovery evidence schema v1

Schema ID: `regelsuche.discovery-evidence/v1`

## Published artifacts

- JSON Schema: [`docs/schemas/regelsuche.discovery-evidence-v1.schema.json`](schemas/regelsuche.discovery-evidence-v1.schema.json)
- Valid examples: [`docs/examples/discovery-evidence/v1/`](examples/discovery-evidence/v1)
- Generated complete example: [`docs/generated/discovery/complete-square/evidence.json`](generated/discovery/complete-square/evidence.json)

## Mandatory profiles

| Profile | Required additions |
| --- | --- |
| `observed` | producer, subject, observations, claims, generalized hypothesis |
| `validated` | observed + provenance, assumptions, oracle results |
| `promoted` | validated + ablation, novelty, promotion |
| `public` | promoted + canonical evidence ID/hash, artifacts, and confirmed proof when `proof.required=true` |

## Annotated complete example

The generated complete-square evidence document is the reference example for the full public profile.

- `schemaId` and `profiles` identify the stable contract and the profile ladder reached by the document.
- `canonicalEvidenceId` / `canonicalEvidenceHash` are the deterministic identifiers derived from the canonical payload plus sibling artifact hashes.
- `producer` records the implementation-independent producer metadata.
- `subject` names the mathematical discovery target without relying on Java class names.
- `claims`, `observations`, `generalizedHypothesis`, `revisions`, `assumptions`, `counterexamples`, and `holdouts` separate what was observed from what was inferred.
- `oracleResults`, `ablation`, and `proof` encode external validation, performance evidence, and formal confirmation state, including inconclusive outcomes.
- `promotion` records whether the candidate passed the public release gate.
- `artifacts` stores semantic sibling hashes (`summary.md`, `search-space.svg`) so tampering changes the recomputed canonical hash.

## Trust model

The schema distinguishes:

- observations (`observations[*].outcome`)
- generated summaries and generalized hypotheses (`claims[*].kind`)
- external validations (`oracleResults[*].kind`)
- formal confirmations (`proof`)
- inconclusive outcomes (`INCONCLUSIVE`, `NOT_REQUESTED`, `UNAVAILABLE`)

## Migration from the previous flat report shape

Legacy generated evidence fields remain mirrored for compatibility, but new consumers should prefer the stable v1 sections:

| Previous field | v1 location |
| --- | --- |
| `generatedBy` | `producer.producerId` |
| `scenarioId`, `inputExpression`, `targetExpression` | `subject.*` |
| `bridgeRulesUsed`, `learnedMacros`, `reusedMacros` | `observations[0]` |
| `oracleStatus`, `oracleEvidence` | `oracleResults[0]` |
| `withMacroRun`, `withoutMacroRun` | `holdouts[*]` and `ablation.*` |
| `promotionEligible` | `promotion.eligible` |
| sibling files (`summary.md`, `search-space.svg`) | `artifacts[*]` |

Old reports that only know the flat fields can keep working during migration, while new exporters and validators should treat the v1 sections as canonical.
