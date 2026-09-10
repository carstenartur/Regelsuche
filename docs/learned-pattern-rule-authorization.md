# Learned pattern rule authorization

Issue #745 requires a production-facing boundary beyond the existing exact
pattern promotion. `LearnedPatternRulePromoter` proves the mathematical pattern
identity and binds caller-supplied evidence hashes, but the v1 promoter alone
does not load those artifacts.

`LearnedPatternRuleAuthorizationService` adds the missing fail-closed evidence
layer without changing the mathematical proof contract. It deliberately does
**not** trust a generic file that merely says `PASSED`: where Regelsuche already
has a native self-verifying artifact, that artifact is parsed and its semantic
invariants are checked directly.

## Authorization flow

```text
EvolutionGenome + selected RewriteGene
             |
             v
EvidenceBundle
  genome + gene + repository revision
  issuedAt / expiresAt
  hashes of the four concrete evidence roots
             |
             +-----------------------+
             |                       |
             v                       v
EvolutionSplitManifest       EvolutionValidationSelection
  TRAIN/VALIDATION/TEST       deterministic VALIDATION winner
  disjointness/leakage        no correctness/reachability blockers
             |                       |
             +-----------+-----------+
                         |
                         v
              EvolutionFinalTestEvaluation
                exact selected genome/config
                same split + validation selection
                qualificationEligible == true
                         |
                         v
              CounterexampleEvidence
                exact gene/repository subject
                frozen scalar/commutative budget
                deterministic search replay
                NO_COUNTEREXAMPLE_FOUND
                no inferred assumptions
                         |
                         v
              LearnedPatternRulePromoter
                exact polynomial identity proof
                         |
                         v
 learned-pattern-rule-authorization-receipt/v1
```

The bundle contains no success status. It only supplies identity, lifetime and
content-addressed links. Therefore changing a status claim in the bundle cannot
authorize anything: the domain artifacts themselves determine qualification.

## Leakage and held-out binding

`EvolutionSplitManifest` is the leakage root. Its existing constructor verifies
that TRAIN, VALIDATION and FINAL TEST are disjoint by case ID, family, exact
signature, alpha signature, input identity and hidden-target identity. The
authorizer additionally requires its derived TRAIN scope to equal the exact
`EvolutionGenome.trainingScope()`.

`EvolutionValidationSelection` must reference that split manifest, must have
selected the exact genome being promoted, and the selected configuration must
remain `eligible()`. Its selected case IDs and families must equal the concrete
VALIDATION partition in the split manifest.

`EvolutionFinalTestEvaluation` must continue the same split and exact validation
selection, retain the same selected genome/configuration and satisfy
`qualificationEligible()`. Its case IDs and families must equal the concrete
FINAL TEST partition. Technical failures, reachability regressions and
correctness failures therefore block authorization rather than being hidden by
an aggregate PASS label.

This slice verifies the **revealed** VALIDATION/FINAL-TEST artifacts used for the
production decision. It does not claim to replace the pre-reveal custody and
sealed-commitment protocol. That stronger custody chain currently lives in
`EvolutionRewriteProgramHeldOutCommitment` and related rewrite-program classes.
Generalizing that boundary without program-specific naming belongs to the next
#745 program/replay slice rather than being silently reused under the wrong
semantic name here.

## Deterministic counterexample evidence

There was no durable content-addressed counterexample artifact matching the
promotion boundary, so this slice adds
`regelsuche.learned-pattern-rule-counterexample-evidence/v1`.

The authorizer converts pattern placeholders to distinct scalar variables while
preserving literal variables, then runs the existing
`DeterministicCounterexampleSearchService` with a frozen budget:

- 64 deterministic numeric random samples;
- boundary values enabled;
- rational samples enabled;
- complex samples enabled;
- random seed `745`;
- matrix assignments disabled;
- no wall-clock timeout.

Matrix assignments are intentionally excluded here because the v1 mathematical
promoter proves identities in a **commutative polynomial ring**. Noncommutative
matrix semantics belong to typed matrix/operator rule contracts, not to this
scalar polynomial authorization lane.

The retained counterexample evidence binds the canonical source/target pattern,
the concrete replay expressions, the complete budget, terminal status,
attempted sources, inferred assumptions, explanation and a hash over the full
runtime result (including a counterexample or typed assumptions if present).
Authorization reruns the service and requires the result hash and retained
fields to match. Only `NO_COUNTEREXAMPLE_FOUND` with non-empty attempted sources,
no concrete counterexample and no inferred/typed assumptions is accepted.

## Time and repository identity

`regelsuche.learned-pattern-rule-authorization-bundle/v1` binds:

- exact genome content hash and gene ID;
- exact lower-case 40-character repository commit revision;
- explicit `issuedAt` and `expiresAt` instants;
- the split-manifest, validation-selection, FINAL-TEST and counterexample hashes.

The authorization call receives an explicit `asOf` instant. No implicit system
clock participates in reproducible tests or qualification. An expired or
not-yet-valid bundle fails closed.

The resulting `LearnedPatternAuthorizationReceipt` expires with the bundle and
binds the exact promotion result, promoted rule content hash and applicability
schema in addition to all verified evidence identities.

## Stored receipt admission

A stored authorization receipt is not treated as a bearer token. Production
startup/admission can call `verifyAuthorization(...)`, which:

1. strictly parses the retained receipt;
2. checks its genome, gene and repository-revision subject;
3. reconstructs the entire authorization at the receipt's original
   `authorizedAt` instant;
4. reloads split, VALIDATION and FINAL TEST artifacts;
5. reruns deterministic counterexample search;
6. reruns the exact pattern promotion proof;
7. requires the reconstructed receipt to equal the retained receipt;
8. finally checks that the retained authorization is still valid at the current
   explicit `asOf` instant.

Thus a valid-looking stored receipt cannot survive replacement of a bound root,
a changed promoted-rule identity or expiration. The replay test explicitly
replaces a VALIDATION root with another internally valid artifact after receipt
issuance and verifies fail-closed rejection.

## Schemas

This slice adds:

- `docs/schemas/regelsuche-learned-pattern-rule-authorization-bundle-v1.schema.json`;
- `docs/schemas/regelsuche-learned-pattern-rule-counterexample-evidence-v1.schema.json`;
- `docs/schemas/regelsuche-learned-pattern-rule-authorization-receipt-v1.schema.json`.

Existing native evolution artifacts keep their existing schemas and codecs; no
parallel VALIDATION or split model is introduced.

## Claim boundary

This contract authorizes only the existing narrow, assumption-free, exactly
proved learned **pattern rule** path. Counterexample search is additional
refutation evidence; it does not replace the exact proof.

The contract does not authorize conditional learned rules and does not authorize
a `RewriteProgram`. Programs have different semantics: sequence, choice,
repetition, guards, pruning and multiple entry/exit paths must be replayed as a
program. Issue #745 therefore keeps learned-program authorization as a separate
follow-up rather than disguising a program as one pattern rewrite.

The legacy promotion receipt remains the mathematical promotion product. Code
that needs the production qualification boundary must use the authorization
service and its stronger receipt.
