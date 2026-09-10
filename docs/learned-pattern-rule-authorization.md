# Learned pattern rule authorization

Issue #745 requires a production-facing boundary beyond the existing exact
pattern promotion. `LearnedPatternRulePromoter` proves the mathematical pattern
identity and binds caller-supplied evidence hashes, but the v1 promoter alone
does not load those evidence artifacts.

`LearnedPatternRuleAuthorizationService` adds the missing fail-closed evidence
layer without changing the mathematical proof contract.

## Authorization flow

```text
EvolutionGenome + selected RewriteGene
             |
             v
four independently produced evidence roots
  - SEMANTIC_VALIDATION / PASSED
  - COUNTEREXAMPLE_SEARCH / NO_COUNTEREXAMPLE_FOUND
  - HOLDOUT_EVALUATION / PASSED
  - LEAKAGE_AUDIT / PASSED
             |
             v
LearnedPatternRuleAuthorizationService
  - strict JSON / duplicate-key rejection
  - exact role set
  - genomeHash + geneId subject binding
  - repositoryRevision binding
  - issuedAt / expiresAt validation at explicit asOf
  - root content-hash verification
             |
             v
LearnedPatternRulePromoter
  - genome preflight
  - exact polynomial pattern identity proof
  - promoted PatternRewriteRule
  - RewriteApplicabilitySchema
  - promotion receipt
             |
             v
learned-pattern-rule-authorization-receipt/v1
```

The authorization lifetime ends at the earliest expiry of the four evidence
roots. `AuthorizationReceipt.requireUsableAt(...)` rechecks time, repository
revision and promoted-rule identity before later use.

## Evidence-root contract

Each root uses
`regelsuche.learned-rule-promotion-evidence-root/v1` and contains exactly:

- evidence role and role-specific terminal status;
- the exact genome content hash and gene ID;
- the exact repository commit revision;
- `issuedAt` and `expiresAt` UTC instants;
- the content hash of the independently retained underlying artifact;
- a content-addressed root hash over all semantic fields.

The four files must be distinct regular files. Missing files, symbolic links,
duplicate JSON keys, unknown/missing fields, invalid hashes, wrong subjects,
wrong revisions, wrong roles, failed terminal states, future evidence and
expired evidence all fail closed.

The schemas are:

- `docs/schemas/regelsuche-learned-rule-promotion-evidence-root-v1.schema.json`
- `docs/schemas/regelsuche-learned-pattern-rule-authorization-receipt-v1.schema.json`

## Claim boundary

This contract authorizes only the existing narrow, assumption-free, exactly
proved learned **pattern rule** path. It does not turn empirical search success
into mathematical authority, does not authorize conditional rules, and does not
authorize a `RewriteProgram`.

Programs have different semantics: sequence, choice, repetition, guards,
pruning and multiple entry/exit paths must be replayed as a program. Issue #745
therefore keeps learned-program authorization as a separate follow-up rather
than disguising a program as one pattern rewrite.

The legacy promotion receipt remains useful as the mathematical promotion
product. Production-facing code should use the authorization service when
external validation/counterexample/holdout/leakage evidence is required.
