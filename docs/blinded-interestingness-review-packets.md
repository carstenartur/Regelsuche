# Blinded interestingness-review packet export

## Purpose

`InterestingnessBlindReviewPacketExporter` turns a frozen independent-review study plan into reviewer-specific packets without exposing candidate identity, split assignment, ranking, system provenance, family labels or private reviewer assignments.

This is operational tooling for issue #389. It does **not** create expert reviews, consensus, qualification evidence or empirical interestingness results.

## Trust boundary

The exporter writes two deliberately separate trees:

```text
review-export/
  public/
    manifest.json
    packets/<packetId>.json
  private/
    assignment-manifest.json
```

Only `public/` is suitable for distribution to reviewers. `private/assignment-manifest.json` binds packet IDs to reviewer, case and candidate identities and must remain access controlled with the reviewer-hash salt and qualification/conflict records.

Public packets include only the frozen reviewer instructions, blinded presentation, declared scales and rationale codes. They explicitly retain `reviewCollectionStatus=NOT_COLLECTED`.

## Determinism and integrity

Assignments are canonicalized by assignment ID. Presentation bytes and reviewer instructions must match the hashes frozen in the study plan. Duplicate assignment IDs, duplicate reviewer/candidate pairs, unknown cases, missing presentations, unexpected materials and insufficient reviewer coverage fail closed.

Every packet and manifest is content-addressed. Map serialization is key-ordered so repeated exports are byte-identical. Directory export uses a staging tree and replaces the previous tree as one unit, preventing stale packets from surviving a new revision.

## Verification

```bash
./gradlew :regelsuche-learning:test \
  --tests de.regelsuche.mining.InterestingnessBlindReviewPacketExporterTest
```

The characterization requires deterministic output, public/private separation, absence of private and split identifiers from public packets, rejection of malformed assignments and removal of stale files.

## Claim boundary

Repository fixtures may verify software and leakage boundaries only. A counted study still requires real qualified independent reviewers, conflict checks, privacy governance, collection freeze, intake, consensus, CALIBRATION-only selection and one-time TEST evaluation under the existing #332/#389 protocol.

## Custodian-operated local submission and freeze

On a single POSIX host, use a dedicated local operating-system account and a trusted parent directory outside the checkout. The command creates a new owner-only vault. The custodian authenticates the submitting reviewer through the assignment delivery channel and supplies the matching pseudonym from the private manifest. Reviewers receive only their individual public packet, registration commitment and opaque submission receipt. The command is an offline custodian interface. Access to the custodian account includes access to the underlying records.

For a synthetic rehearsal, run the following from the checkout. Absolute paths keep the vault and materials independent of the Gradle project directory. The first command prints the registration commitment. It recomputes the export and binds the policy bytes, reviewer instructions, plan, evidence origin and both manifest identities before accepting submissions.

```sh
./gradlew -q :regelsuche-learning:restrictedReviewCollection \
  --args="init /private/study/vault /private/study/plan.json /private/study/exchange /private/study/policy.md DEVELOPMENT_FIXTURE"
./gradlew -q :regelsuche-learning:restrictedReviewCollection \
  --args="submit /private/study/vault $authenticated_reviewer_hash" < /private/study/form.json
./gradlew -q :regelsuche-learning:restrictedReviewCollection --args="freeze /private/study/vault"
(umask 077; ./gradlew -q :regelsuche-learning:restrictedReviewCollection \
  --args="audit /private/study/vault" > /private/study/frozen-audit.json)
```

A rated form has this shape. Replace the three registration/packet placeholders with values from this delivery, and choose values from that packet's scales and codebook.

```json
{
  "registrationHash": "<registration commitment>",
  "packetId": "<packet ID>",
  "packetContentHash": "<packet content hash>",
  "revision": 1,
  "disposition": "RATED",
  "blind": true,
  "independent": true,
  "relevancePermille": 750,
  "confidencePermille": 500,
  "rationaleCodes": ["technical-interest"],
  "optionalProse": "Optional private note"
}
```

The custodian fixes origin at registration. For external ratings the custodian adds the verified `qualificationEvidenceHash` before submission. The receipt binds the blind and independent declarations to the registered packet, revision, assignment and qualification evidence. An `ABSTAINED` form carries both declarations and omits all label fields. For an `EXCLUDED` assignment, the custodian records the actual declarations and a `reason` of `WITHDRAWN`, `CONFLICT`, `EXPOSURE` or `INELIGIBLE`. An exclusion has no label. Optional prose resides in separate access-controlled files and only its commitment enters the receipt.

The vault serializes submission and freeze across processes with a file lock and replaces the state by atomic rename. A second submission for the same assignment is refused. The audit command only opens after freeze and exposes the full private assignment roster with rated, excluded, abstained and missing slots. The freeze includes the existing intake report, the exposure history and policy/packet commitments. Repeated freeze returns the same commitment. The registration and freeze commands return only a hash. The audit itself contains private pseudonyms and labels.

### Linked corrections and one TEST exposure

When held-out analysis will be required, pass a private profile file as the last argument of `init`. Its format is a JSON array of the existing Java `InterestingnessProfileCalibration.CaseProfiles` record values with `caseId`, `candidateId`, `theoryDiscovery` and `searchReuse`. Serialize the assessment records with a regular Jackson mapper. The custodian verifies provenance against the original candidate artifacts, then registration checks the theory assessment identity against the frozen case, validates both profiles against identical raw evidence, and commits the actual profile inputs before accepting labels.

```sh
./gradlew -q :regelsuche-learning:restrictedReviewCollection \
  --args="init /private/study/vault /private/study/plan.json /private/study/exchange /private/study/policy.md DEVELOPMENT_FIXTURE /private/study/profiles.json"
./gradlew -q :regelsuche-learning:restrictedReviewCollection --args="evaluate /private/study/vault"
./gradlew -q :regelsuche-learning:restrictedReviewCollection \
  --args="revise /private/study/vault $previous_freeze_hash WITHDRAWAL"
(umask 077; ./gradlew -q :regelsuche-learning:restrictedReviewCollection \
  --args="audit /private/study/vault 1" > /private/study/revision-1.json)
```

Run `evaluate` after freezing. The first completed analysis records the exposed TEST artifact hashes against the study and freeze. Retries return the stored receipt. The audit contains the consensus, both predictor metrics, the CALIBRATION-only selection commitment, TEST ranking, Pareto front, profile-order agreement, leave-one-out stability, sensitivity and the gate decision under the registered thresholds. Incomplete or discordant panels retain their blockers and exposure history. Fixture diagnostics use only fixture consensus and zero expert counts, even if the numerical gate accepts the ranking. Empirical consensus, promotion and public evidence remain unevaluated for fixtures.

A revision requires the exact predecessor freeze receipt and a reason of `TRANSCRIPTION`, `WITHDRAWAL`, `INCIDENT` or `ATTESTATION_CORRECTION`. Unchanged slots carry forward. Submit a correction with the new revision number, or an `EXCLUDED` withdrawal, then freeze again. Earlier revisions remain addressable, and a replacement receipt links to its predecessor. The predictive identity remains fixed. Each labelled revision binds its immediately preceding labelled identity. A TEST exposure on any earlier revision prevents fresh analysis of a correction. Historical snapshots and prose deletion remain subject to the consent and retention policy.

A counted study still requires approved governance, independent human reviewers and a separate independent audit. [Restricted review policy draft](independent-review-governance-draft.md)
