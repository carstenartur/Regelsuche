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

This command supports initial collection revision 1. Linked corrections, post-submission withdrawals, controlled consensus analysis and the single TEST exposure require a subsequent workflow before a counted study. The governance draft describes those responsibilities. [Restricted review policy draft](independent-review-governance-draft.md)
